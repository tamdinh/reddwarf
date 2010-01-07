/*
 * Copyright 2007-2009 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.impl.service.nodemap.affinity;

import com.sun.sgs.impl.kernel.StandardProperties;
import com.sun.sgs.impl.service.nodemap.affinity.graph.AffinityGraphBuilder;
import com.sun.sgs.impl.service.nodemap.affinity.graph.GraphListener;
import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import com.sun.sgs.impl.sharedutil.PropertiesWrapper;
import com.sun.sgs.kernel.ComponentRegistry;
import com.sun.sgs.kernel.NodeType;
import com.sun.sgs.profile.ProfileCollector;
import com.sun.sgs.service.TransactionProxy;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A driver for the Label Propagation Algorithm affinity group finders.
 * The driver is responsible for instantiating the affinity group system.
 * <p>
 * A driver must be instantiated on each node of the Darkstar cluster.
 * <p>
 * The following property is supported:
 * <p>
 * <dl style="margin-left: 1em">
 *
 * <dt>	<i>Property:</i> <code><b>
 *   {@value #GRAPH_CLASS_PROPERTY}
 *	</b></code><br>
 *	<i>Default:</i> When configured for multi-node:
 *      {@value #DEFAULT_GRAPH_CLASS}, otherwise {@value #GRAPH_CLASS_NONE}
 * <br>
 *
 * <dd style="padding-top: .5em">The graph builder to use.  Set to
 *   {@value #GRAPH_CLASS_NONE} if no affinity group finding is required, which
 *   is useful for testing. <p>
 * </dl>
 */
public class LPADriver {
    /** The base name for properties. */
    private static final String PROP_NAME =
            "com.sun.sgs.impl.service.nodemap.affinity";
    /** The property for specifying the graph builder class. */
    public static final String GRAPH_CLASS_PROPERTY =
        PROP_NAME + ".graphbuilder.class";

    /**
     * The value to be given to {@code GRAPH_CLASS_PROPERTY} if no
     * affinity group finding should be instantiated (useful for testing).
     */
    public static final String GRAPH_CLASS_NONE = "None";

    /** The default graph builder class. */
    static final String DEFAULT_GRAPH_CLASS = GRAPH_CLASS_NONE;
//    "com.sun.sgs.impl.service.nodemap.affinity.dlpa.graph.WeightedGraphBuilder";


    private static final LoggerWrapper logger =
            new LoggerWrapper(Logger.getLogger(PROP_NAME));

    /** A graph listener, used for detecting affinity groups, or {@code null}
     *  if no graph listener is being used.
     */
    private final GraphListener graphListener;
    
    /** The affinity graph builder, null if there is none. */
    private final AffinityGraphBuilder graphBuilder;

    /** Are we shutdown? */
    private boolean shutdown = false;

    /**
     * Constructs an instance of this class with the specified properties.
     * <p>
     * @param	properties the properties for configuring this subsystem
     * @param	systemRegistry the registry of available system components
     * @param	txnProxy the transaction proxy
     *
     * @throws Exception if an error occurs during creation
     */
    public LPADriver(Properties properties,
                     ComponentRegistry systemRegistry,
                     TransactionProxy txnProxy)
        throws Exception
    {
        PropertiesWrapper wrappedProps = new PropertiesWrapper(properties);
        
        NodeType type =
            NodeType.valueOf(
                properties.getProperty(StandardProperties.NODE_TYPE));

        // The default for single node is NONE
        String builderName =
                wrappedProps.getProperty(GRAPH_CLASS_PROPERTY,
                                         type == NodeType.singleNode ?
                                                 GRAPH_CLASS_NONE :
                                                 DEFAULT_GRAPH_CLASS);

        if (GRAPH_CLASS_NONE.equals(builderName)) {
            // do not instantiate anything
            graphBuilder = null;
            graphListener = null;
            return;
        }
            
        // A builder was specified or we are running multi-node
        graphBuilder = wrappedProps.getClassInstanceProperty(
                    GRAPH_CLASS_PROPERTY, DEFAULT_GRAPH_CLASS,
                    AffinityGraphBuilder.class,
                    new Class[] { Properties.class,
                                  ComponentRegistry.class,
                                  TransactionProxy.class },
                    properties, systemRegistry, txnProxy);

        // Add the self as listener if there is a builder and we are
        // not a core server node.
        if (graphBuilder != null && type != NodeType.coreServerNode) {
            ProfileCollector col =
                systemRegistry.getComponent(ProfileCollector.class);
            graphListener = new GraphListener(graphBuilder);
            col.addListener(graphListener, false);
        } else {
            graphListener = null;
        }
        logger.log(Level.CONFIG,
                   "Created LPADriver with listener: " + graphListener +
                   ", builder: " + graphBuilder + ", and properties:" +
                   "\n  " + GRAPH_CLASS_PROPERTY + "=" + builderName);
    }

    /**
     * Shuts down all resources created by this driver.
     */
    public synchronized void shutdown() {
        if (!shutdown) {
            logger.log(Level.FINE, "BuilderFactory shut down");
            
            if (graphListener != null) {
                graphListener.shutdown();
            }
            if (graphBuilder != null) {
                graphBuilder.shutdown();
            }
            shutdown = true;
        }
    }

    /**
     * Returns the graph builder used by this driver, or {@code null} if
     * we are not building graphs.
     * @return the graph builder or {@code null} if there is none
     */
    public AffinityGraphBuilder getGraphBuilder() {
        return graphBuilder;
    }

    /**
     * Returns the graph listener used by this driver, or {@code null} if
     * we are not building graphs.
     * @return the graph listener or {@code null} if there is none
     */
    public GraphListener getGraphListener() {
        return graphListener;
    }
}