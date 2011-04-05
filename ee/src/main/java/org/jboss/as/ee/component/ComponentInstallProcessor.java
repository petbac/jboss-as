/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.ee.component;

import org.jboss.as.ee.naming.ContextNames;
import org.jboss.as.ee.naming.NamespaceSelectorService;
import org.jboss.as.ee.naming.RootContextService;
import org.jboss.as.naming.ManagedReferenceFactory;
import org.jboss.as.naming.NamingStore;
import org.jboss.as.naming.context.NamespaceContextSelector;
import org.jboss.as.naming.service.BinderService;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.reflect.DeploymentReflectionIndex;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.value.InjectedValue;
import org.jboss.msc.value.Value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class ComponentInstallProcessor implements DeploymentUnitProcessor {

    public void deploy(final DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        final Module module = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.MODULE);
        if (module == null) {
            // Nothing to do
            return;
        }
        final EEModuleDescription moduleDescription = deploymentUnit.getAttachment(Attachments.EE_MODULE_DESCRIPTION);
        // Iterate through each component, installing it into the container
        for (AbstractComponentDescription description : moduleDescription.getComponentDescriptions()) {
            try {
                deployComponent(phaseContext, description);
            }
            catch (RuntimeException e) {
                throw new DeploymentUnitProcessingException("Failed to install component " + description, e);
            }
        }
    }

    protected void deployComponent(final DeploymentPhaseContext phaseContext, final AbstractComponentDescription description) throws DeploymentUnitProcessingException {

        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        final Module module = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.MODULE);
        final ModuleClassLoader classLoader = module.getClassLoader();
        final EEModuleDescription moduleDescription = deploymentUnit.getAttachment(Attachments.EE_MODULE_DESCRIPTION);
        final DeploymentReflectionIndex deploymentReflectionIndex = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.REFLECTION_INDEX);
        final ServiceTarget serviceTarget = phaseContext.getServiceTarget();

        final JndiInjectionPointStore moduleInjectionPointStore = deploymentUnit.getAttachment(Attachments.MODULE_INJECTIONS);
        final JndiInjectionPointStore injectionPointStore;
        if(moduleInjectionPointStore != null) {
            injectionPointStore = new JndiInjectionPointStore(moduleInjectionPointStore);
        } else {
            injectionPointStore = new JndiInjectionPointStore();
        }

        final String className = description.getComponentClassName();
        final Class<?> componentClass;
        try {
            componentClass = Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new DeploymentUnitProcessingException("Component class not found", e);
        }
        final String applicationName = description.getApplicationName();
        final String moduleName = description.getModuleName();
        final String componentName = description.getComponentName();
        final ServiceName baseName = deploymentUnit.getServiceName().append("component").append(componentName);

        final AbstractComponentConfiguration configuration = description.createComponentConfiguration(phaseContext, componentClass);
        configuration.setComponentClass(componentClass);

        //create additional injectors
        final List<ServiceName> additionalDependencies = new ArrayList<ServiceName>();
        for (InjectionFactory injectionFactory : moduleDescription.getInjectionFactories()) {
            final ComponentInjector injector = injectionFactory.createInjector(configuration);
            if (injector != null) {
                configuration.addComponentInjector(injector);
                ServiceName injectorServiceName = injector.getServiceName();
                if (injector.getServiceName() != null) {
                    additionalDependencies.add(injectorServiceName);
                }
            }
        }

        final ServiceName createServiceName = baseName.append("CREATE");
        final ServiceName startServiceName = baseName.append("START");
        final ComponentCreateService createService = new ComponentCreateService(configuration);
        final ServiceBuilder<Component> createBuilder = serviceTarget.addService(createServiceName, createService);
        final ComponentStartService startService = new ComponentStartService();
        final ServiceBuilder<Component> startBuilder = serviceTarget.addService(startServiceName, startService);

        // Add all service dependencies
        Map<ServiceName, InjectedValue<Object>> injections = configuration.getDependencyInjections();
        for (Map.Entry<ServiceName, ServiceBuilder.DependencyType> entry : description.getDependencies().entrySet()) {
            createBuilder.addDependency(entry.getValue(), entry.getKey(), injections.get(entry.getKey()));
        }

        final ServiceName appContextServiceName = ContextNames.contextServiceNameOfApplication(applicationName);
        final ServiceName moduleContextServiceName = ContextNames.contextServiceNameOfModule(applicationName, moduleName);
        final ServiceName componentContextServiceName;

        switch (description.getNamingMode()) {
            case CREATE: {
                componentContextServiceName = ContextNames.contextServiceNameOfComponent(applicationName, moduleName, componentName);
                // And, create the context...
                RootContextService contextService = new RootContextService();
                serviceTarget.addService(componentContextServiceName, contextService)
                        .addDependency(createServiceName)
                        .install();
                break;
            }
            case USE_MODULE: {
                componentContextServiceName = moduleContextServiceName;
                break;
            }
            default: {
                componentContextServiceName = null;
                break;
            }
        }

        final NamespaceSelectorService selectorService = new NamespaceSelectorService();
        final ServiceName selectorServiceName = baseName.append("NAMESPACE");
        final ServiceBuilder<NamespaceContextSelector> selectorServiceBuilder = serviceTarget.addService(selectorServiceName, selectorService)
                .addDependency(appContextServiceName, NamingStore.class, selectorService.getApp())
                .addDependency(moduleContextServiceName, NamingStore.class, selectorService.getModule());
        if (componentContextServiceName != null) {
            selectorServiceBuilder.addDependency(componentContextServiceName, NamingStore.class, selectorService.getComp());
        }
        selectorServiceBuilder.install();

        // START depends on CREATE
        startBuilder.addDependency(createServiceName, AbstractComponent.class, startService.getComponentInjector());
        //add dependencies on the injector services
        startBuilder.addDependencies(additionalDependencies);

        // Iterate through each view, creating the services for each
        for (ViewConfiguration viewConfiguration : configuration.getViews()) {
            final ServiceName serviceName = viewConfiguration.getViewServiceName();
            final ViewService viewService = new ViewService(viewConfiguration);
            serviceTarget.addService(serviceName, viewService)
                    .addDependency(createServiceName, Component.class, viewService.getComponentInjector())
                    .install();
            for (BindingConfiguration bindingConfiguration : viewConfiguration.getBindingConfigurations()) {
                final String bindingName = bindingConfiguration.getName();
                final BinderService service = new BinderService(bindingName);
                ServiceBuilder<ManagedReferenceFactory> serviceBuilder = serviceTarget.addService(ContextNames.serviceNameOfContext(applicationName, moduleName, componentName, bindingName), service);
                bindingConfiguration.getResourceValue(serviceBuilder, phaseContext, service.getManagedObjectInjector());
            }
        }

        // Iterate through each binding/injection, creating the JNDI binding and wiring dependencies for each
        final List<BindingDescription> bindingDescriptions = description.getMergedBindings();
        for (BindingDescription bindingDescription : bindingDescriptions) {
            addJndiBinding(classLoader, serviceTarget, applicationName, moduleName, componentName, createServiceName, startBuilder, bindingDescription, phaseContext, injectionPointStore);
        }

        // Now iterate the interceptors and their bindings
        final Collection<InterceptorDescription> interceptorClasses = description.getAllInterceptors().values();

        for (InterceptorDescription interceptorDescription : interceptorClasses) {
            String interceptorClassName = interceptorDescription.getInterceptorClassName();
            final Class<?> interceptorClass;
            try {
                interceptorClass = Class.forName(interceptorClassName, false, classLoader);
            } catch (ClassNotFoundException e) {
                throw new DeploymentUnitProcessingException("Component interceptor class not found", e);
            }

            List<ResourceInjection> injectionList = injectionPointStore.applyInjections(interceptorClass, deploymentReflectionIndex);

            configuration.addInterceptorResourceInjection(interceptorClass,injectionList);
        }
        List<ResourceInjection> injectionList = injectionPointStore.applyInjections(componentClass, deploymentReflectionIndex);
        //we want to make sure that all JNDI entries are available when the component is created.
        startBuilder.addDependencies(injectionPointStore.getServiceNames());
        configuration.getResourceInjections().addAll(injectionList);

        createBuilder.install();
        startBuilder.install();
    }

    private static void addJndiBinding(final ModuleClassLoader classLoader, final ServiceTarget serviceTarget, final String applicationName, final String moduleName, final String componentName, final ServiceName createServiceName, final ServiceBuilder<Component> startBuilder, final BindingDescription bindingDescription, final DeploymentPhaseContext phaseContext, final JndiInjectionPointStore injectionPointStore) throws DeploymentUnitProcessingException {
        // Gather information about the dependency
        final String bindingName = bindingDescription.getBindingName();
        final String bindingType = bindingDescription.getBindingType();
        try {
            // Sanity check before we invest a lot of effort
            Class.forName(bindingType, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new DeploymentUnitProcessingException("Component binding class not found", e);
        }

        // There are four applicable scenarios for an injectable dependency
        // 1. The source is a service, and the resource is bound into JNDI (start->binding->service)
        // 2. The source is a service but the resource is not bound into JNDI (start->service)
        // 3. The source is not a service and the resource is bound into JNDI (start->binding)
        // 4. The source is not a service and the resource is not bound (no dependency)

        Value<ManagedReferenceFactory> resourceValue;

        // Check to see if this entry should actually be bound into JNDI.
        if (bindingName != null) {
            // bind into JNDI
            final String serviceBindingName;

            int idx = bindingName.indexOf('/');
            if (idx == -1) {
                serviceBindingName = bindingName;
            } else {
                serviceBindingName = bindingName.substring(idx + 1);
            }
            final BinderService service = new BinderService(serviceBindingName);
            final ServiceName bindingServiceName = ContextNames.serviceNameOfContext(applicationName, moduleName, componentName, bindingName);
            if (bindingServiceName == null) {
                throw new IllegalArgumentException("Invalid context name '" + bindingName + "' for binding");
            }
            // The service builder for the binding
            ServiceBuilder<ManagedReferenceFactory> sourceServiceBuilder = serviceTarget.addService(bindingServiceName, service);
            // The resource value is determined by the reference source, which may add a dependency on the original value to the binding
            bindingDescription.getReferenceSourceDescription().getResourceValue(bindingDescription, sourceServiceBuilder, phaseContext, service.getManagedObjectInjector());
            resourceValue = sourceServiceBuilder
                    .addDependency(createServiceName)
                    .addDependency(bindingServiceName.getParent(), NamingStore.class, service.getNamingStoreInjector())
                    .install();
            // Start service depends on the binding if the binding is a dependency
            if (bindingDescription.isDependency()) startBuilder.addDependency(bindingServiceName);

            for(final InjectionTargetDescription injectionTarget : bindingDescription.getInjectionTargetDescriptions()) {
                injectionPointStore.addInjectedValue(injectionTarget,resourceValue,bindingServiceName);
            }
        } else {
            // do not bind into JNDI
            // The resource value comes from the reference source, which may add a dependency on the original value to the start service
            final InjectedValue<ManagedReferenceFactory> injectedValue = new InjectedValue<ManagedReferenceFactory>();
            bindingDescription.getReferenceSourceDescription().getResourceValue(bindingDescription, startBuilder, phaseContext, injectedValue);
            resourceValue = injectedValue;
            for(final InjectionTargetDescription injectionTarget : bindingDescription.getInjectionTargetDescriptions()) {
                injectionPointStore.addInjectedValue(injectionTarget,resourceValue,null);
            }
        }
    }

    public void undeploy(final DeploymentUnit context) {
    }
}
