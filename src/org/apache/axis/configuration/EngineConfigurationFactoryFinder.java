/*
 * The Apache Software License, Version 1.1
 *
 *
 * Copyright (c) 2002 The Apache Software Foundation.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:
 *       "This product includes software developed by the
 *        Apache Software Foundation (http://www.apache.org/)."
 *    Alternately, this acknowledgment may appear in the software itself,
 *    if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The names "Axis" and "Apache Software Foundation" must
 *    not be used to endorse or promote products derived from this
 *    software without prior written permission. For written
 *    permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache",
 *    nor may "Apache" appear in their name, without prior written
 *    permission of the Apache Software Foundation.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 */

package org.apache.axis.configuration;

import java.lang.reflect.Method;

import org.apache.axis.EngineConfigurationFactory;
import org.apache.axis.components.logger.LogFactory;
import org.apache.axis.utils.JavaUtils;
import org.apache.commons.discovery.ResourceClassIterator;
import org.apache.commons.discovery.ResourceNameIterator;
import org.apache.commons.discovery.resource.ClassLoaders;
import org.apache.commons.discovery.resource.classes.DiscoverClasses;
import org.apache.commons.discovery.resource.names.DiscoverServiceNames;
import org.apache.commons.discovery.tools.ClassUtils;
import org.apache.commons.logging.Log;


/**
 * This is a default implementation of EngineConfigurationFactory.
 * It is user-overrideable by a system property without affecting
 * the caller. If you decide to override it, use delegation if
 * you want to inherit the behaviour of this class as using
 * class extension will result in tight loops. That is, your
 * class should implement EngineConfigurationFactory and keep
 * an instance of this class in a member field and delegate
 * methods to that instance when the default behaviour is
 * required.
 *
 * @author Richard A. Sitze
 */
public class EngineConfigurationFactoryFinder
{
    protected static Log log =
        LogFactory.getLog(EngineConfigurationFactoryFinder.class.getName());

    private static final Class mySpi = EngineConfigurationFactory.class;
    private static final Class myFactory = EngineConfigurationFactoryFinder.class;

    private static final Class[] newFactoryParamTypes =
        new Class[] { Object.class };

    private static final String requiredMethod =
        "public static EngineConfigurationFactory newFactory(Object)";

    private EngineConfigurationFactoryFinder() {
    }


    /**
     * Create the default engine configuration and detect whether the user
     * has overridden this with their own.
     *
     * The discovery mechanism will use the following logic:
     *
     * - discover all available EngineConfigurationFactories
     *   - find all META-INF/services/org.apache.axis.EngineConfigurationFactory
     *     files available through class loaders.
     *   - read files (see Discovery) to obtain implementation(s) of that
     *     interface
     * - For each impl, call 'newFactory(Object param)'
     * - Each impl should examine the 'param' and return a new factory ONLY
     *   - if it knows what to do with it
     *     (i.e. it knows what to do with the 'real' type)
     *   - it can find it's configuration information
     * - Return first non-null factory found.
     * - Try EngineConfigurationFactoryServlet.newFactory(obj)
     * - Try EngineConfigurationFactoryDefault.newFactory(obj)
     * - If zero found (all return null), throw exception
     *
     * ***
     * This needs more work: System.properties, etc.
     * Discovery will have more tools to help with that
     * (in the manner of use below) in the near future.
     * ***
     *
     */
    public static EngineConfigurationFactory newFactory(Object obj) {
        /**
         * recreate on each call is critical to gaining
         * the right class loaders.  Do not cache.
         */
        Object[] params = new Object[] { obj };

        /**
         * Find and examine each service
         */
        ClassLoaders loaders =
            ClassLoaders.getAppLoaders(mySpi, myFactory, true);

        ResourceNameIterator it =
            new DiscoverServiceNames(loaders).findResourceNames(mySpi.getName());

        ResourceClassIterator services =
            new DiscoverClasses(loaders).findResourceClasses(it);

        EngineConfigurationFactory factory = null;
        while (factory == null  &&  services.hasNext()) {
            Class service = services.nextResourceClass().loadClass();

            factory = newFactory(service, newFactoryParamTypes, params);
        }

        if (factory == null) {
            try {
                factory = EngineConfigurationFactoryServlet.newFactory(obj);
            } catch (Exception e) {
                log.warn(JavaUtils.getMessage("engineConfigInvokeNewFactory",
                                              EngineConfigurationFactoryServlet.class.getName(),
                                              requiredMethod), e);
            }

            if (factory == null) {
                try {
                    // should NEVER return null.
                    factory = EngineConfigurationFactoryDefault.newFactory(obj);
                } catch (Exception e) {
                    log.warn(JavaUtils.getMessage("engineConfigInvokeNewFactory",
                                                  EngineConfigurationFactoryDefault.class.getName(),
                                                  requiredMethod), e);
                }
            }
        }

        if (factory != null) {
            log.debug(JavaUtils.getMessage("engineFactory", factory.getClass().getName()));
        } else {
            log.error(JavaUtils.getMessage("engineConfigFactoryMissing"));
            // we should be throwing an exception here,
            //
            // but again, requires more refactoring than we want to swallow
            // at this point in time.  Ifthis DOES occur, it's a coding error:
            // factory should NEVER be null.
            // Testing will find this, as NullPointerExceptions will be generated
            // elsewhere.
        }

        return factory;
    }

    public static EngineConfigurationFactory newFactory() {
        return newFactory(null);
    }

    private static EngineConfigurationFactory newFactory(Class service,
                                                         Class[] paramTypes,
                                                         Object[] param) {
        /**
         * Verify that service implements:
         *  public static EngineConfigurationFactory newFactory(Object);
         */
        Method method =
            ClassUtils.findPublicStaticMethod(service,
                                              EngineConfigurationFactory.class,
                                              "newFactory",
                                              paramTypes);


        if (method == null) {
            log.warn(JavaUtils.getMessage("engineConfigMissingNewFactory",
                                          service.getName(),
                                          requiredMethod));
        } else {
            try {
                return (EngineConfigurationFactory)method.invoke(null, param);
            } catch (Exception e) {
                log.warn(JavaUtils.getMessage("engineConfigInvokeNewFactory",
                                              service.getName(),
                                              requiredMethod), e);
            }
        }

        return null;
    }
}