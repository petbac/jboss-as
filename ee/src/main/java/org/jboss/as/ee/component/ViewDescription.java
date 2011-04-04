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

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.invocation.ImmediateInterceptorFactory;
import org.jboss.invocation.Interceptor;
import org.jboss.invocation.InterceptorContext;
import org.jboss.invocation.proxy.MethodIdentifier;

/**
 * A description of a view.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public class ViewDescription {
    private final String viewClassName;
    private final AbstractComponentDescription componentDescription;
    private final List<String> viewNameParts = new ArrayList<String>();
    private final Set<String> bindingNames = new HashSet<String>();
    private final Deque<ViewConfigurator> configurators = new ArrayDeque<ViewConfigurator>();

    /**
     * Construct a new instance.
     *
     * @param componentDescription the associated component description
     * @param viewClassName the view class name
     */
    public ViewDescription(final AbstractComponentDescription componentDescription, final String viewClassName) {
        this.componentDescription = componentDescription;
        this.viewClassName = viewClassName;
        configurators.addFirst(new DefaultConfigurator());
    }

    /**
     * Get the view's class name.
     *
     * @return the class name
     */
    public String getViewClassName() {
        return viewClassName;
    }

    /**
     * Get the associated component description.
     *
     * @return the component description
     */
    public AbstractComponentDescription getComponentDescription() {
        return componentDescription;
    }

    /**
     * Get the strings to append to the view base name.  The view base name is the component base name
     * followed by {@code "VIEW"} followed by these strings.
     *
     * @return the list of strings
     */
    public List<String> getViewNameParts() {
        return viewNameParts;
    }

    /**
     * Get the set of binding names for this view.
     *
     * @return the set of binding names
     */
    public Set<String> getBindingNames() {
        return bindingNames;
    }

    /**
     * Iterate over the configurators for this view.
     *
     * @return the iterable
     */
    public Iterable<ViewConfigurator> getConfigurators() {
        return configurators;
    }

    static final ImmediateInterceptorFactory CLIENT_DISPATCHER_INTERCEPTOR_FACTORY = new ImmediateInterceptorFactory(new Interceptor() {
        public Object processInvocation(final InterceptorContext context) throws Exception {
            ComponentViewInstance viewInstance = context.getPrivateData(ComponentViewInstance.class);
            return viewInstance.getEntryPoint(context.getMethod()).processInvocation(context);
        }
    });

    private static class DefaultConfigurator implements ViewConfigurator {

        public void configure(final AbstractComponentConfiguration componentConfiguration, final ViewDescription description, final ViewConfiguration configuration) {
            // TODO get the component method map off the component config
            final Map<MethodIdentifier, Method> componentMethodMap = null;
            Method[] methods = configuration.getProxyFactory().getCachedMethods();
            for (Method method : methods) {
                final Method componentMethod = componentMethodMap.get(MethodIdentifier.getIdentifierForMethod(method));
                configuration.getViewInterceptorDeque(method).addLast(new ImmediateInterceptorFactory(new ComponentDispatcherInterceptor(componentMethod)));
                configuration.getClientInterceptorDeque(method).addLast(CLIENT_DISPATCHER_INTERCEPTOR_FACTORY);
            }
        }
    }

    private static class ComponentDispatcherInterceptor implements Interceptor {

        private final Method componentMethod;

        public ComponentDispatcherInterceptor(final Method componentMethod) {
            this.componentMethod = componentMethod;
        }

        public Object processInvocation(final InterceptorContext context) throws Exception {
            ComponentInstance componentInstance = context.getPrivateData(ComponentInstance.class);
            if (componentInstance == null) {
                throw new IllegalStateException("No component instance associated");
            }
            Method oldMethod = context.getMethod();
            try {
                context.setMethod(componentMethod);
                context.setTarget(componentInstance.getInstance());
                return componentInstance.getInterceptor(componentMethod).processInvocation(context);
            } finally {
                context.setMethod(oldMethod);
                context.setTarget(null);
            }
        }
    }
}
