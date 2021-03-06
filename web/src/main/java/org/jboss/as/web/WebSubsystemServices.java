/**
 *
 */
package org.jboss.as.web;

import org.jboss.msc.service.ServiceName;

/**
 * Service name constants.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class WebSubsystemServices {

    /** The base name for jboss.web services. */
    public static final ServiceName JBOSS_WEB = ServiceName.JBOSS.append("web");
    /** The jboss.web server name, there can only be one. */
    public static final ServiceName JBOSS_WEB_SERVER = JBOSS_WEB.append("server");
    /** The base name for jboss.web connector services. */
    public static final ServiceName JBOSS_WEB_CONNECTOR = JBOSS_WEB.append("connector");
    /** The base name for jboss.web host services. */
    public static final ServiceName JBOSS_WEB_HOST = JBOSS_WEB.append("host");


    private WebSubsystemServices() {
    }
}
