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


import org.jboss.as.naming.context.NamespaceContextSelector;
import org.jboss.invocation.Interceptor;
import org.jboss.invocation.InterceptorContext;
import org.jboss.invocation.InterceptorFactory;
import org.jboss.invocation.InterceptorFactoryContext;
import org.jboss.invocation.InterceptorInstanceFactory;
import org.jboss.invocation.SimpleInterceptorFactoryContext;
import org.jboss.msc.value.InjectedValue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.jboss.as.ee.component.SecurityActions.getContextClassLoader;
import static org.jboss.as.ee.component.SecurityActions.setContextClassLoader;

/**
 * The parent of all component classes.
 *
 * @author John Bailey
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public abstract class AbstractComponent implements Component {

    static final Object INSTANCE_KEY = new Object();

    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];

    /**
     * An interceptor instance factory which will get an instance attached to the interceptor factory
     * context.
     */
    public static final InterceptorInstanceFactory INSTANCE_FACTORY = new InterceptorInstanceFactory() {
        public Object createInstance(final InterceptorFactoryContext context) {
            return context.getContextData().get(INSTANCE_KEY);
        }
    };

    private final String componentName;
    private final Class<?> componentClass;
    private final List<ResourceInjection> resourceInjections;
    private final List<ComponentLifecycle> postConstructMethods;
    private final List<ComponentLifecycle> preDestroyMethods;
    private final List<LifecycleInterceptorFactory> postConstructInterceptorsMethods;
    private final List<LifecycleInterceptorFactory> preDestroyInterceptorsMethods;
    private final List<ComponentInjector> componentInjectors;
    private Interceptor componentInterceptor;
    private final Map<Method, InterceptorFactory> interceptorFactoryMap;
    private final Map<Class<?>, List<LifecycleInterceptorFactory>> interceptorPreDestroys;
    private final InjectedValue<NamespaceContextSelector> namespaceContextSelectorInjector = new InjectedValue<NamespaceContextSelector>();
    private final Map<Class<?>, ComponentView> views = new HashMap<Class<?>, ComponentView>();
    @Deprecated
    private final Collection<Method> componentMethods;

    private volatile boolean gate;

    /**
     * Construct a new instance.
     *
     * @param configuration the component configuration
     */
    protected AbstractComponent(final AbstractComponentConfiguration configuration) {
        componentName = configuration.getComponentName();
        componentClass = configuration.getComponentClass();
        resourceInjections = configuration.getResourceInjections();
        postConstructMethods = configuration.getPostConstructComponentLifecycles();
        preDestroyMethods = configuration.getPreDestroyComponentLifecycles();
        postConstructInterceptorsMethods = configuration.getPostConstructLifecycles();
        preDestroyInterceptorsMethods = configuration.getPreDestroyLifecycles();
        interceptorFactoryMap = configuration.getInterceptorFactoryMap();
        interceptorPreDestroys = configuration.getInterceptorPreDestroys();
        this.componentInjectors = configuration.getComponentInjectors();
        this.componentMethods = configuration.getComponentMethods();
    }

    /**
     * {@inheritDoc}
     */
    public ComponentInstance createInstance() {
        if (!gate) {
            // Block until successful start
            synchronized (this) {
                while (!gate) {
                    // TODO: check for failure condition
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Component not available (interrupted)");
                    }
                }
            }
        }
        //we must use the same context over the life of the instance
        SimpleInterceptorFactoryContext interceptorContext = new SimpleInterceptorFactoryContext();
        Object objectInstance = createObjectInstance();

        List<Interceptor> preDestoryInterceptors = new ArrayList<Interceptor>();
        createPreDestroyMethods(interceptorContext, preDestoryInterceptors);

        //apply injections, and add the clean up interceptors to the pre destroy chain
        //we want interceptors that clean up injections to be last in the interceptor chain
        //so the injections are not cleaned up until all @AroundInvoke methods have been run
        preDestoryInterceptors.addAll(applyInjections(objectInstance));

        AbstractComponentInstance instance = constructComponentInstance(objectInstance, preDestoryInterceptors, interceptorContext);

        performPostConstructLifecycle(instance, interceptorContext);

        // process the interceptors bound to individual methods
        // the interceptors are tied to the lifecycle of the instance
        final Map<Method, InterceptorFactory> factoryMap = getInterceptorFactoryMap();
        final Map<Method, Interceptor> methodMap = new IdentityHashMap<Method, Interceptor>(factoryMap.size());
        interceptorContext.getContextData().put(AbstractComponent.INSTANCE_KEY, objectInstance);
        for (Map.Entry<Method, InterceptorFactory> entry : factoryMap.entrySet()) {
            Method method = entry.getKey();
            PerViewMethodInterceptorFactory.populate(interceptorContext, this, instance, method);
            InterceptorFactory interceptorFactory = entry.getValue();
            assert interceptorFactory != null : "Can't find interceptor factory for " + method;
            methodMap.put(method, interceptorFactory.create(interceptorContext));
        }
        instance.setMethodMap(methodMap);
        return instance;
    }

    /**
     * Create a new component object instance.  After the instance is constructed, injections and lifecycle methods will
     * be called upon it.
     *
     * @return the new instance
     */
    protected Object createObjectInstance() {
        try {
            Object instance = componentClass.newInstance();
            return instance;
        } catch (InstantiationException e) {
            InstantiationError error = new InstantiationError(e.getMessage());
            error.setStackTrace(e.getStackTrace());
            throw error;
        } catch (IllegalAccessException e) {
            IllegalAccessError error = new IllegalAccessError(e.getMessage());
            error.setStackTrace(e.getStackTrace());
            throw error;
        }
    }

    /**
     * Construct the component instance.  The object instance will have injections and lifecycle invocations completed
     * already.
     *
     * @param instance               the object instance to wrap
     * @param preDestroyInterceptors the interceptors to run on pre-destroy
     * @return the component instance
     */
    protected abstract AbstractComponentInstance constructComponentInstance(Object instance, List<Interceptor> preDestroyInterceptors, InterceptorFactoryContext context);

    /**
     * Get the class of this bean component.
     *
     * @return the class
     */
    public Class<?> getComponentClass() {
        return componentClass;
    }

    /**
     * Get the name of this bean component.
     *
     * @return
     */
    public String getComponentName() {
        return this.componentName;
    }

    /**
     * Apply the injections to a newly retrieved bean instance.
     *
     * @param instance The bean instance
     * @return A list of interceptors that perform any required cleanup of injected objects when the component's lifecycle ends
     */
    protected List<Interceptor> applyInjections(final Object instance) {
        final List<ResourceInjection> resourceInjections = this.resourceInjections;
        if (resourceInjections != null) {
            for (ResourceInjection resourceInjection : resourceInjections) {
                resourceInjection.inject(instance);
            }
        }
        List<ComponentInjector.InjectionHandle> injectionHandles = new ArrayList<ComponentInjector.InjectionHandle>();
        for (ComponentInjector injector : componentInjectors) {
            injectionHandles.add(injector.inject(instance));
        }
        return Collections.<Interceptor>singletonList(new UninjectionInterceptor(injectionHandles));
    }

    /**
     * Perform any post-construct life-cycle routines.  By default this will run any post-construct methods.
     *
     * @param instance           The bean instance
     * @param interceptorContext
     */
    protected void performPostConstructLifecycle(final ComponentInstance instance, final InterceptorFactoryContext interceptorContext) {
        final List<LifecycleInterceptorFactory> postConstructInterceptorMethods = this.postConstructInterceptorsMethods;

        final List<Interceptor> postConstructs = new ArrayList<Interceptor>(postConstructInterceptorMethods.size());
        for (final LifecycleInterceptorFactory postConstructMethod : postConstructInterceptorMethods) {
            postConstructs.add(postConstructMethod.create(interceptorContext));
        }
        performLifecycle(instance, postConstructs, postConstructMethods);
    }

    /**
     * {@inheritDoc}
     */
    public void destroyInstance(final ComponentInstance instance) {
        performPreDestroyLifecycle(instance);
        performInterceptorPreDestroyLifecycle(instance);
    }

    protected void createPreDestroyMethods(final InterceptorFactoryContext context, List<Interceptor> interceptors) {
        for (LifecycleInterceptorFactory method : preDestroyInterceptorsMethods) {
            interceptors.add(method.create(context));
        }
    }

    /**
     * Perform any pre-destroy life-cycle routines.  By default it will invoke all pre-destroy methods.
     *
     * @param instance The bean instance
     */
    protected void performPreDestroyLifecycle(final ComponentInstance instance) {
        performLifecycle(instance, instance.getPreDestroyInterceptors(), preDestroyMethods);
    }

    /**
     * Perform any pre-destroy life-cycle routines found on class level interceptors.  By default it will invoke all pre-destroy methods.
     *
     * @param instance The bean instance
     */
    protected void performInterceptorPreDestroyLifecycle(final ComponentInstance instance) {
        final InterceptorFactoryContext interceptorFactoryContext = instance.getInterceptorFactoryContext();
        for (Map.Entry<Class<?>, List<LifecycleInterceptorFactory>> entry : interceptorPreDestroys.entrySet()) {
            final Class<?> interceptorClass = entry.getKey();
            final Object interceptorInstance = interceptorFactoryContext.getContextData().get(interceptorClass);
            if (interceptorInstance == null) {
                throw new RuntimeException("Failed to perform PreDestroy method.  No instance found for interceptor class " + interceptorClass);
            }

            final List<Interceptor> preDestroys = new ArrayList<Interceptor>();
            for (LifecycleInterceptorFactory interceptorFactory : entry.getValue()) {
                preDestroys.add(interceptorFactory.create(interceptorFactoryContext));
            }
            performLifecycle(interceptorInstance, preDestroys);
        }
    }


    private void performLifecycle(final Object instance, final Iterable<Interceptor> lifeCycleInterceptors) {
        Iterator<Interceptor> interceptorIterator = lifeCycleInterceptors.iterator();
        if (interceptorIterator.hasNext()) {
            final ClassLoader contextCl = getContextClassLoader();
            setContextClassLoader(componentClass.getClassLoader());
            try {
                while (interceptorIterator.hasNext()) {
                    try {
                        final Interceptor interceptor = interceptorIterator.next();
                        final InterceptorContext context = new InterceptorContext();
                        //as we use LifecycleInterceptorFactory we do not need to set the method
                        context.setTarget(instance instanceof ComponentInstance ?
                            (ComponentInstance)((ComponentInstance) instance).getInstance() :
                            instance);

                        context.setContextData(new HashMap<String, Object>());
                        context.setParameters(EMPTY_OBJECT_ARRAY);
                        interceptor.processInvocation(context);
                    } catch (Throwable t) {
                        throw new RuntimeException("Failed to invoke post construct method for class " + getComponentClass(), t);
                    }
                }
            } finally {
                setContextClassLoader(contextCl);
            }
        }
    }

    private void performLifecycle(final ComponentInstance instance, final Iterable<Interceptor> lifeCycleInterceptors, final Collection<ComponentLifecycle> componentLifecycles) {
        Iterator<Interceptor> interceptorIterator = lifeCycleInterceptors.iterator();
        if (interceptorIterator.hasNext() || (componentLifecycles != null && !componentLifecycles.isEmpty())) {
            final ClassLoader contextCl = getContextClassLoader();
            setContextClassLoader(componentClass.getClassLoader());
            try {
                while (interceptorIterator.hasNext()) {
                    try {
                        final Interceptor interceptor = interceptorIterator.next();
                        final InterceptorContext context = new InterceptorContext();
                        //as we use LifecycleInterceptorFactory we do not need to set the method
                        context.setTarget(instance);
                        context.setContextData(new HashMap<String, Object>());
                        context.setParameters(EMPTY_OBJECT_ARRAY);
                        interceptor.processInvocation(context);
                    } catch (Throwable t) {
                        throw new RuntimeException("Failed to invoke post construct method for class " + getComponentClass(), t);
                    }
                }

                // Execute the life-cycle
                for (ComponentLifecycle preDestroyMethod : componentLifecycles) {
                    try {
                        preDestroyMethod.invoke((ComponentInstance)instance);
                    } catch (Throwable t) {
                        throw new RuntimeException("Failed to invoke method for class " + getComponentClass(), t);
                    }
                }
            } finally {
                setContextClassLoader(contextCl);
            }
        }
    }

    public List<ResourceInjection> getResourceInjections() {
        return Collections.unmodifiableList(resourceInjections);
    }

    public NamespaceContextSelector getNamespaceContextSelector() {
        return namespaceContextSelectorInjector.getValue();
    }

    // TODO: Jaikiran - Temporary to avoid compilation errors
    InjectedValue<NamespaceContextSelector> getNamespaceContextSelectorInjector() {
        return namespaceContextSelectorInjector;
    }

    /**
     * {@inheritDoc}
     */
    public void start() {
        synchronized (this) {
            gate = true;
            notifyAll();
        }
    }

    /**
     * {@inheritDoc}
     */
    public void stop() {
        synchronized (this) {
            gate = false;
        }
    }

    Map<Method, InterceptorFactory> getInterceptorFactoryMap() {
        return interceptorFactoryMap;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object createRemoteProxy(final Class<?> view, final ClassLoader targetClassLoader, final Interceptor clientInterceptor) {
        throw new UnsupportedOperationException("One thing at a time!");
    }

    protected Interceptor getComponentInterceptor() {
        assert componentInterceptor != null : "componentInterceptor is null";
        return componentInterceptor;
    }

    void setComponentInterceptor(Interceptor interceptor) {
        this.componentInterceptor = interceptor;
    }

    /**
     * Interceptor that cleans up injected resources
     */
    private static class UninjectionInterceptor implements Interceptor {

        private final List<ComponentInjector.InjectionHandle> injections;

        public UninjectionInterceptor(List<ComponentInjector.InjectionHandle> injections) {
            this.injections = injections;
        }

        @Override
        public Object processInvocation(InterceptorContext context) throws Exception {
            for (ComponentInjector.InjectionHandle injectionHandle : injections) {
                injectionHandle.uninject();
            }
            return null;
        }
    }

    /**
     * Because interceptors are bound to a methods identity, you need the exact method
     * so find the interceptor. This should really be done during deployment via
     * reflection index and not during runtime operations.
     *
     * @param other     another method with the exact same signature
     * @return the method to which interceptors have been bound
     */
    @Deprecated
    public Method getComponentMethod(Method other) {
        for (Method id : componentMethods) {
            if (other.equals(id))
                return id;
        }
        throw new IllegalArgumentException("Can't find method " + other);
    }

    public ComponentView getComponentView(Class<?> viewClass) {
        return views.get(viewClass);
    }
}
