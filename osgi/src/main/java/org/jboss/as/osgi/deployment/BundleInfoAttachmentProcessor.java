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

package org.jboss.as.osgi.deployment;

import java.io.IOException;
import java.util.jar.Manifest;

import org.jboss.as.deployment.attachment.ManifestAttachment;
import org.jboss.as.deployment.attachment.VirtualFileAttachment;
import org.jboss.as.deployment.unit.DeploymentUnitContext;
import org.jboss.as.deployment.unit.DeploymentUnitProcessingException;
import org.jboss.as.deployment.unit.DeploymentUnitProcessor;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.osgi.spi.util.BundleInfo;
import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.vfs.VFSUtils;
import org.jboss.vfs.VirtualFile;
import org.osgi.framework.BundleException;

/**
 * Processes deployments that contain a valid OSGi manifest.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 20-Sep-2010
 */
public class BundleInfoAttachmentProcessor implements DeploymentUnitProcessor {

    private ServiceContainer serviceContainer;

    public BundleInfoAttachmentProcessor(ServiceContainer serviceContainer) {
        this.serviceContainer = serviceContainer;
    }

    @Override
    public void processDeployment(DeploymentUnitContext context) throws DeploymentUnitProcessingException {

        // Check if we already have an OSGi deployment
        BundleInfo info = BundleInfoAttachment.getBundleInfoAttachment(context);
        if (info != null)
            return;

        // Get the manifest from the deployment's virtual file
        VirtualFile virtualFile = VirtualFileAttachment.getVirtualFileAttachment(context);
        Manifest manifest = ManifestAttachment.getManifestAttachment(context);
        if (manifest == null) {
            // Check if this virtual file contains a valid OSGi manifest
            // If so attach the BundleInfo and the Deployment abstraction
            try {
                manifest = VFSUtils.getManifest(virtualFile);
                if (manifest != null)
                    ManifestAttachment.attachManifest(context, manifest);
            } catch (IOException ex) {
                throw new DeploymentUnitProcessingException("Cannot read manifest from: " + virtualFile);
            }
        }

        // Nothing to do if this is not a valid manifest
        if (BundleInfo.isValidateBundleManifest(manifest)) {
            // Construct and attach the {@link BundleInfo}
            try {
                String location = InstallBundleInitiatorService.getLocation(serviceContainer, context.getName());
                info = BundleInfo.createBundleInfo(AbstractVFS.adapt(virtualFile), location);
                BundleInfoAttachment.attachBundleInfo(context, info);
            } catch (BundleException ex) {
                throw new DeploymentUnitProcessingException("Cannot create bundle deployment from: " + virtualFile);
            }
        }
    }
}