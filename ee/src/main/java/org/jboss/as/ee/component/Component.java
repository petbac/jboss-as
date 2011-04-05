/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
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
import org.jboss.msc.service.ServiceName;

import java.util.Map;

import java.io.Serializable;

/**
 * Common contract for an EE component.  Implementations of this will be available as a service and can be used as the
 * backing for a JNDI ObjectFactory reference.
 *
 * @author John Bailey
 * @author <a href="mailto:cdewolf@redhat.com">Carlo de Wolf</a>
 */
public interface Component {

    /**
     * Start operation called when the Component is available.
     */
    void start();

    /**
     * Stop operation called when the Component is no longer available.
     */
    void stop();

    /**
     * Get the component's actual implementation class.
     *
     * @return the component class
     */
    Class<?> getComponentClass();

    /**
     * Create a new instance of this component.  This may be invoked by a component interceptor, a client interceptor,
     * or in the course of creating a new client, or in the case of an "eager" singleton, at component start.  This
     * method will block until the component is available.  If the component fails to start then a runtime exception
     * will be thrown.
     *
     * @return the component instance
     */
    ComponentInstance createInstance();

    /**
     * Destroy an instance of the component.  This method causes all uninjection and pre-destroy lifecycle invocations
     * to occur.
     *
     * @param instance the instance to destroy
     */
    void destroyInstance(ComponentInstance instance);

    /**
     * Create a new client interceptor for this component.  The returned interceptor will contain the necessary logic to
     * locate the appropriate instance.
     * <p>
     * The given view type must be one of the registered view types for this component.
     *
     * @param view the view type
     * @return the client entry point
     */
    Interceptor createClientInterceptor(Class<?> view);

    /**
     * Create a new client interceptor for this component.  The returned interceptor will contain the necessary logic to
     * locate the appropriate instance. The interceptor will be associated with the given sessionId
     * <p>
     * The given view type must be one of the registered view types for this component.
     *
     * @param view the view type
     * @return the client entry point
     */
    Interceptor createClientInterceptor(Class<?> view,Serializable sessionId);

    /**
     * Create a remote client proxy for this component.
     *
     * @param view the view type class
     * @param targetClassLoader the target class loader for the proxy
     * @param clientInterceptor the client interceptor to use for this proxy.
     * @return the proxy instance
     */
    Object createRemoteProxy(Class<?> view, ClassLoader targetClassLoader, Interceptor clientInterceptor);

    /**
     * Get the naming context selector for this component.
     *
     * @return the selector
     */
    NamespaceContextSelector getNamespaceContextSelector();

    /**
     * The view service names keyed by the view class type.
     * @return The view services for this component
     */
    Map<Class<?>,ServiceName> getViewServices();
}
