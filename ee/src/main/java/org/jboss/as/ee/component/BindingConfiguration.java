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

import org.jboss.as.naming.ManagedReferenceFactory;
import org.jboss.as.naming.service.BinderService;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.ServiceBuilder;

/**
 * A binding into JNDI.  This class contains the mechanism to construct the binding service.  In particular
 * it represents <b>only</b> the description of the binding; it does not represent injection or any other parameters
 * of a JNDI resource.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public abstract class BindingConfiguration {
    private final String name;

    /**
     * Construct a new instance.
     *
     * @param name the binding name
     */
    protected BindingConfiguration(final String name) {
        this.name = name;
    }

    /**
     * The name into which this binding should be made.  The meaning of relative names depends on where this
     * binding description is used.  For component bindings, relative names are generally relative to {@code java:comp/env}.
     *
     * @return the name into which this binding should be made
     */
    public String getName() {
        return name;
    }

    /**
     * Get the value to use as the injection source.  The value will be yield an injectable which is dereferenced once
     * for every time the reference source is injected.  The given binder service builder may be used to apply any
     * dependencies for this binding (i.e. the source for the binding's value).
     *
     * @param serviceBuilder the builder for the binder service
     * @param phaseContext the deployment phase context
     * @param injector the injector into which the value should be placed
     */
    public abstract void getResourceValue(ServiceBuilder<?> serviceBuilder, DeploymentPhaseContext phaseContext, Injector<ManagedReferenceFactory> injector);
}
