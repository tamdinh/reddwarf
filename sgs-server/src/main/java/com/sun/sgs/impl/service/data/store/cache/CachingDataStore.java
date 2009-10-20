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

package com.sun.sgs.impl.service.data.store.cache;

import com.sun.sgs.app.ExceptionRetryStatus;
import com.sun.sgs.app.ObjectNotFoundException;
import com.sun.sgs.auth.Identity;
import com.sun.sgs.impl.kernel.StandardProperties;
import com.sun.sgs.impl.service.data.store.AbstractDataStore;
import com.sun.sgs.impl.service.data.store.BindingValue;
import com.sun.sgs.impl.service.data.store.NetworkException;
import com.sun.sgs.impl.service.data.store.cache.
    BasicCacheEntry.AwaitWritableResult;
import com.sun.sgs.impl.service.data.store.cache.
    CachingDataStoreServer.GetBindingForRemoveResults;
import com.sun.sgs.impl.service.data.store.cache.
    CachingDataStoreServer.GetBindingForUpdateResults;
import com.sun.sgs.impl.service.data.store.cache.
    CachingDataStoreServer.GetBindingResults;
import com.sun.sgs.impl.service.data.store.cache.
    CachingDataStoreServer.GetObjectForUpdateResults;
import com.sun.sgs.impl.service.data.store.cache.
    CachingDataStoreServer.GetObjectResults;
import com.sun.sgs.impl.service.data.store.cache.
    CachingDataStoreServer.NextBoundNameResults;
import com.sun.sgs.impl.service.data.store.cache.
    CachingDataStoreServer.NextObjectResults;
import com.sun.sgs.impl.service.data.store.cache.
    CachingDataStoreServer.RegisterNodeResult;
import static com.sun.sgs.impl.service.data.store.cache.BindingKey.LAST;
import static com.sun.sgs.impl.service.data.store.cache.BindingState.BOUND;
import static com.sun.sgs.impl.service.data.store.cache.BindingState.UNBOUND;
import static com.sun.sgs.impl.service.data.store.cache.BindingState.UNKNOWN;
import static com.sun.sgs.impl.service.transaction.
    TransactionCoordinator.TXN_TIMEOUT_PROPERTY;
import static com.sun.sgs.impl.service.transaction.
    TransactionCoordinatorImpl.BOUNDED_TIMEOUT_DEFAULT;
import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import com.sun.sgs.impl.sharedutil.PropertiesWrapper;
import com.sun.sgs.impl.util.Exporter;
import static com.sun.sgs.impl.util.Numbers.addCheckOverflow;
import static com.sun.sgs.kernel.AccessReporter.AccessType.READ;
import static com.sun.sgs.kernel.AccessReporter.AccessType.WRITE;
import com.sun.sgs.kernel.ComponentRegistry;
import com.sun.sgs.kernel.KernelRunnable;
import com.sun.sgs.kernel.NodeType;
import com.sun.sgs.kernel.TransactionScheduler;
import com.sun.sgs.service.DataConflictListener;
import com.sun.sgs.service.Transaction;
import com.sun.sgs.service.TransactionProxy;
import com.sun.sgs.service.WatchdogService;
import com.sun.sgs.service.store.ClassInfoNotFoundException;
import com.sun.sgs.service.store.DataStore;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.rmi.NotBoundException;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import static java.util.logging.Level.CONFIG;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import java.util.logging.Logger;

/**
 * Provides an implementation of {@link DataStore} that caches data on the
 * local node and communicates with a {@link CachingDataStoreServer}. <p>
 *
 * The {@link #CachingDataStore constructor} supports the following
 * configuration properties: <p>
 *
 * <dl style="margin-left: 1em">
 *
 * <dt> <i>Property:</i> <code><b>{@value #CALLBACK_PORT_PROPERTY}</b></code>
 *	<br>
 *	<i>Default:</i> <code>{@value #DEFAULT_CALLBACK_PORT}</code>
 *
 * <dd style="padding-top: .5em">The network port used to accept callback
 *	requests.  The value should be a non-negative number less than
 *	<code>65536</code>.  If the value specified is <code>0</code>, then an
 *	anonymous port will be chosen. <p>
 *
 * <dt> <i>Property:</i> <code><b>{@value #EVICTION_BATCH_SIZE_PROPERTY}
 *	</b></code> <br>
 *	<i>Default:</i> <code>{@value #DEFAULT_EVICTION_BATCH_SIZE}</code>
 *
 * <dd style="padding-top: .5em">The number of entries to consider when
 *	selecting a single candidate for eviction. <p>
 *
 * <dt> <i>Property:</i> <code><b>{@value #EVICTION_RESERVE_SIZE_PROPERTY}
 *	</b></code> <br>
 *	<i>Default:</i> <code>{@value #DEFAULT_EVICTION_RESERVE_SIZE}</code>
 *
 * <dd style="padding-top: .5em">The number of cache entries to hold in reserve
 *	for use while searching for eviction candidates. <p>
 *
 * <dt> <i>Property:</i> <code><b>{@value #LOCK_TIMEOUT_PROPERTY}</b></code>
 *	<br>
 *	<i>Default:</i> {@value #DEFAULT_LOCK_TIMEOUT_PROPORTION} times the
 *	transaction timeout.
 *
 * <dd style="padding-top: .5em">The maximum amount of time in milliseconds
 *	that an attempt to obtain a lock will be allowed to continue before
 *	being aborted.  The value must be greater than <code>0</code>, and
 *	should be less than the transaction timeout. <p>
 *
 * <dt> <i>Property:</i> <code><b>{@value #MAX_RETRY_PROPERTY}</b></code> <br>
 *	<i>Default:</i> <code>{@value #DEFAULT_MAX_RETRY}</code>
 *
 * <dd style="padding-top: .5em">The number of milliseconds to continue
 *	retrying I/O operations before determining that the failure is
 *	permanent.  The value must be non-negative. <p>
 *
 * <dt> <i>Property:</i> <code><b>{@value #NUM_LOCKS_PROPERTY}</b></code> <br>
 *	<i>Default:</i> <code>{@value #DEFAULT_NUM_LOCKS}</code>
 *
 * <dd style="padding-top: .5em">The number of locks used by the cache.  The
 *	value must be greater than <code>0</code>.  The number of cache locks
 *	controls the amount of concurrency. <p>
 *
 * <dt> <i>Property:</i> <code><b>{@value #RETRY_WAIT_PROPERTY}</b></code> <br>
 *	<i>Default:</i> <code>{@value #DEFAULT_RETRY_WAIT}</code>
 *
 * <dd style="padding-top: .5em">The number of milliseconds to wait before
 *	retrying a failed I/O operation.  The value must be non-negative. <p>
 *
 * <dt> <i>Property:</i> <code><b>{@value #SERVER_HOST_PROPERTY}</b></code><br>
 *	<i>Default:</i> the value of the <code>{@value
 *	com.sun.sgs.impl.kernel.StandardProperties#SERVER_HOST}</code>
 *	property, if present, or <code>localhost</code> if this node is
 *	starting the server
 *
 * <dd style="padding-top: .5em">The name of the host running the {@code
 *	CachingDataStoreServer}. <p>
 *
 * <dt> <i>Property:</i> <code><b>{@value #SERVER_PORT_PROPERTY}</b></code>
 *	<br>
 *	<i>Default:</i>	<code>{@value #DEFAULT_SERVER_PORT}</code>
 *
 * <dd style="padding-top: .5em">The network port used to make server requests.
 *	The value should be a non-negative number less than <code>65536</code>.
 *	The value <code>0</code> can only be specified if the <code>{@link
 *	StandardProperties#NODE_TYPE}</code> property is not
 *	<code>appNode</code> and means that an anonymous port will be chosen
 *	for running the server. <p>
 *
 * <dt> <i>Property:</i> <code><b>{@value #CACHE_SIZE_PROPERTY}</b></code>
 *	<br>
 *	<i>Default:</i>	<code>{@value #DEFAULT_CACHE_SIZE}</code>
 *
 * <dd style="padding-top: .5em">The maximum number of entries, including name
 *	bindings and objects, that can be stored in the cache. <p>
 *
 * <dt> <i>Property:</i> <code><b>{@value #UPDATE_QUEUE_SIZE_PROPERTY}
 *	</b></code> <br>
 *	<i>Default:</i>	<code>{@value #DEFAULT_UPDATE_QUEUE_SIZE}</code>
 *
 * <dd style="padding-top: .5em">The maximum number of requests that can be
 *	queued waiting to send to the server.  The value must be no smaller
 *	than <code>{@value #MIN_CACHE_SIZE}</code>. <p>
 *
 * </dl>
 */
public class CachingDataStore extends AbstractDataStore
    implements CallbackServer, FailureReporter
{
    /** The current package. */
    private static final String PKG =
	"com.sun.sgs.impl.service.data.store.cache";

    /** The property for specifying the callback port. */
    public static final String CALLBACK_PORT_PROPERTY = PKG + ".callback.port";

    /** The default callback port. */
    public static final int DEFAULT_CALLBACK_PORT = 44541;

    /**
     * The property for specifying the eviction batch size, which specifies the
     * number of entries to consider when selecting a single candidate for
     * eviction.
     */
    public static final String EVICTION_BATCH_SIZE_PROPERTY =
	PKG + ".eviction.batch.size";

    /** The default eviction batch size size. */
    public static final int DEFAULT_EVICTION_BATCH_SIZE = 100;

    /**
     * The property for specifying the eviction reserve size, which specifies
     * the number of cache entries to hold in reserve for use while searching
     * for eviction candidates.
     */
    public static final String EVICTION_RESERVE_SIZE_PROPERTY =
	PKG + ".eviction.reserve.size";

    /** The default eviction reserve size. */
    public static final int DEFAULT_EVICTION_RESERVE_SIZE = 50;

    /**
     * The property for specifying the number of milliseconds to wait when
     * attempting to obtain a lock.
     */
    public static final String LOCK_TIMEOUT_PROPERTY = PKG + ".lock.timeout";

    /**
     * The proportion of the standard transaction timeout to use for the lock
     * timeout if no lock timeout is specified.
     */
    public static final double DEFAULT_LOCK_TIMEOUT_PROPORTION = 0.2;

    /**
     * The property for specifying the number of milliseconds to continue
     * retrying I/O operations before determining that the failure is
     * permanent.
     */
    public static final String MAX_RETRY_PROPERTY = PKG + ".max.retry";

    /** The default maximum retry, in milliseconds. */
    public static final long DEFAULT_MAX_RETRY = 1000;

    /** The property for specifying the number of cache locks. */
    public static final String NUM_LOCKS_PROPERTY = PKG + ".num.locks";

    /** The default number of cache locks. */
    public static final int DEFAULT_NUM_LOCKS = 20;

    /** The property for specifying the new object ID allocation batch size. */
    public static final String OBJECT_ID_BATCH_SIZE_PROPERTY =
	PKG + ".object.id.batch.size";

    /** The default new object ID allocation batch size. */
    public static final int DEFAULT_OBJECT_ID_BATCH_SIZE = 1000;

    /**
     * The property for specifying the number of milliseconds to wait before
     * retrying a failed I/O operation.
     */
    public static final String RETRY_WAIT_PROPERTY = PKG + ".retry.wait";

    /** The default retry wait, in milliseconds. */
    public static final long DEFAULT_RETRY_WAIT = 10;

    /** The property for specifying the server host. */
    public static final String SERVER_HOST_PROPERTY = PKG + ".server.host";

    /** The property for specifying the server port. */
    public static final String SERVER_PORT_PROPERTY = PKG + ".server.port";

    /** The default server port. */
    public static final int DEFAULT_SERVER_PORT = 44540;

    /** The property for specifying the cache size. */
    public static final String CACHE_SIZE_PROPERTY = PKG + ".size";

    /** The default cache size. */
    public static final int DEFAULT_CACHE_SIZE = 5000;

    /** The minimum cache size. */
    public static final int MIN_CACHE_SIZE = 1000;

    /** The property for specifying the update queue size. */
    public static final String UPDATE_QUEUE_SIZE_PROPERTY =
	PKG + ".update.queue.size";

    /** The default update queue size. */
    public static final int DEFAULT_UPDATE_QUEUE_SIZE = 100;

    /** The property that controls checking bindings. */
    public static final String CHECK_BINDINGS_PROPERTY =
	PKG + ".check.bindings";

    /** The types of binding checks. */
    public enum CheckBindingsType {
	/** Check bindings after each binding operation. */
	OPERATION,

	/** Check bindings at the end of each transaction. */
	TXN,

	/** Don't check bindings. */
	NONE;
    }

    /** The name of this class. */
    private static final String CLASSNAME = PKG + ".CachingDataStore";

    /** The logger for this class. */
    static final LoggerWrapper logger =
	new LoggerWrapper(Logger.getLogger(CLASSNAME));

    /** The logger for transaction abort exceptions thrown by this class. */
    static final LoggerWrapper abortLogger =
	new LoggerWrapper(Logger.getLogger(CLASSNAME + ".abort"));

    /**
     * The number of cache entries to consider when looking for a least
     * recently used entry to evict.
     */
    private final int evictionBatchSize;

    /**
     * The number of cache entries to hold in reserve for use while finding
     * entries to evict.
     */
    private final int evictionReserveSize;

    /** The lock timeout. */
    private final long lockTimeout;

    /** The maximum retry for I/O operations. */
    private final long maxRetry;

    /** The retry wait for failed I/O operations. */
    private final long retryWait;

    /** When to check the consistency of bindings. */
    private final CheckBindingsType checkBindings;

    /** The transaction proxy. */
    final TransactionProxy txnProxy;

    /** The owner for tasks run by the data store. */
    final Identity taskOwner;

    /** The transaction scheduler. */
    private final TransactionScheduler txnScheduler;

    /** The local data store server, if started, else {@code null}. */
    private final CachingDataStoreServerImpl localServer;

    /** The remote data store server. */
    final CachingDataStoreServer server;

    /** The exporter for the callback server. */
    private final Exporter<CallbackServer> callbackExporter =
	new Exporter<CallbackServer>(CallbackServer.class);

    /**
     * The thread that evicts least recently used entries from the cache as
     * needed.
     */
    private final EvictionThread evictionThread = new EvictionThread();

    /** The node ID for the local node. */
    final long nodeId;

    /** Manages sending updates to the server. */
    final UpdateQueue updateQueue;

    /** The transaction context map for this class. */
    private final TxnContextMap contextMap;

    /** The cache of binding and object entries. */
    private final Cache cache;

    /** The cache of object IDs available for new objects. */
    private final NewObjectIdCache newObjectIdCache;

    /** The number of evictions that have been scheduled but not completed. */
    private final AtomicInteger pendingEvictions = new AtomicInteger();

    /** A thread pool for fetching data from the server. */
    private ExecutorService fetchExecutor =
	Executors.newCachedThreadPool(
	    new NamedThreadFactory(CLASSNAME + ".fetch-"));

    /** The possible shutdown states. */
    enum ShutdownState {

	/** Shutdown has not been requested. */
	NOT_REQUESTED,

	/** Shutdown has been requested. */
	REQUESTED,

	/** All active transactions have been completed. */
	TXNS_COMPLETED,

	/** Shutdown has been completed. */
	COMPLETED;
    }

    /**
     * The shutdown state.  Synchronize on {@link #shutdownSync} when accessing
     * this field.
     */
    private ShutdownState shutdownState = ShutdownState.NOT_REQUESTED;

    /**
     * The number of active transactions.  Synchronize on {@link #shutdownSync}
     * when accessing this field.
     */
    private int txnCount = 0;

    /** Synchronizer for {@code shutdownState}. */
    private final Object shutdownSync = new Object();

    /**
     * The watchdog service, or {@code null} if not initialized.  Synchronize
     * on {@link #watchdogServiceSync} when accessing.
     */
    private WatchdogService watchdogService;

    /**
     * An exception responsible for a failure before the watchdog service
     * became available, or {@code null} if there was no failure.  Synchronize
     * on {@link #watchdogServiceSync} when accessing.
     */
    private Throwable failureBeforeWatchdog;

    /**
     * The synchronizer for {@link #watchdogService} and {@link
     * #failureBeforeWatchdog}.
     */
    private final Object watchdogServiceSync = new Object();

    /** A synchronized list of registered data conflict listeners. */
    private final List<DataConflictListener> dataConflictListeners =
	new ArrayList<DataConflictListener>();

    /** A concurrent deque of conflicting data accesses */
    private final BlockingDeque<DataConflict> dataConflicts =
	new LinkedBlockingDeque<DataConflict>();

    /**
     * The thread that delivers information about conflicting accesses to data
     * conflict listeners.
     */
    private final DataConflictThread dataConflictThread =
	new DataConflictThread();

    /* -- Constructors -- */

    /**
     * Creates an instance of this class.
     *
     * @param	properties the properties for configuring this instance
     * @param	systemRegistry the registry of available system components
     * @param	txnProxy the transaction proxy
     * @throws	Exception if there is a problem creating the data store
     */
    public CachingDataStore(Properties properties,
			    ComponentRegistry systemRegistry,
			    TransactionProxy txnProxy)
	throws Exception
    {
	super(systemRegistry, txnProxy, logger, abortLogger);
	PropertiesWrapper wrappedProps = new PropertiesWrapper(properties);
	NodeType nodeType = wrappedProps.getEnumProperty(
	    StandardProperties.NODE_TYPE, NodeType.class, NodeType.singleNode);
	boolean startServer = (nodeType != NodeType.appNode);
	int callbackPort = wrappedProps.getIntProperty(
	    CALLBACK_PORT_PROPERTY, DEFAULT_CALLBACK_PORT, 0, 65535);
	int cacheSize = wrappedProps.getIntProperty(
	    CACHE_SIZE_PROPERTY, DEFAULT_CACHE_SIZE, MIN_CACHE_SIZE,
	    Integer.MAX_VALUE);
	evictionBatchSize = wrappedProps.getIntProperty(
	    EVICTION_BATCH_SIZE_PROPERTY, DEFAULT_EVICTION_BATCH_SIZE,
	    1, cacheSize);
	evictionReserveSize = wrappedProps.getIntProperty(
	    EVICTION_RESERVE_SIZE_PROPERTY, DEFAULT_EVICTION_RESERVE_SIZE,
	    0, cacheSize);
	long txnTimeout = wrappedProps.getLongProperty(
	    TXN_TIMEOUT_PROPERTY, BOUNDED_TIMEOUT_DEFAULT);
	long defaultLockTimeout =
	    Math.max(1, (long) (txnTimeout * DEFAULT_LOCK_TIMEOUT_PROPORTION));
	lockTimeout = wrappedProps.getLongProperty(
	    LOCK_TIMEOUT_PROPERTY, defaultLockTimeout, 1, Long.MAX_VALUE);
	maxRetry = wrappedProps.getLongProperty(
	    MAX_RETRY_PROPERTY, DEFAULT_MAX_RETRY, 0, Long.MAX_VALUE);
	int numLocks = wrappedProps.getIntProperty(
	    NUM_LOCKS_PROPERTY, DEFAULT_NUM_LOCKS, 1, Integer.MAX_VALUE);
	int objectIdBatchSize = wrappedProps.getIntProperty(
	    OBJECT_ID_BATCH_SIZE_PROPERTY, DEFAULT_OBJECT_ID_BATCH_SIZE,
	    1, Integer.MAX_VALUE);
	retryWait = wrappedProps.getLongProperty(
	    RETRY_WAIT_PROPERTY, DEFAULT_RETRY_WAIT, 0, Long.MAX_VALUE);
	String serverHost = wrappedProps.getProperty(
	    SERVER_HOST_PROPERTY,
	    wrappedProps.getProperty(StandardProperties.SERVER_HOST));
	if (serverHost == null && !startServer) {
	    throw new IllegalArgumentException(
		"A server host must be specified");
	}
	int serverPort = wrappedProps.getIntProperty(
	    SERVER_PORT_PROPERTY, DEFAULT_SERVER_PORT, startServer ? 0 : 1,
	    65535);
	int updateQueueSize = wrappedProps.getIntProperty(
	    UPDATE_QUEUE_SIZE_PROPERTY, DEFAULT_UPDATE_QUEUE_SIZE,
	    1, Integer.MAX_VALUE);
	checkBindings = wrappedProps.getEnumProperty(
	    CHECK_BINDINGS_PROPERTY, CheckBindingsType.class,
	    CheckBindingsType.NONE);
	if (logger.isLoggable(CONFIG)) {
	    logger.log(CONFIG,
		       "Creating CachingDataStore with properties:" +
		       "\n  callback port: " + callbackPort +
		       "\n  eviction batch size: " + evictionBatchSize +
		       "\n  eviction reserve size: " + evictionReserveSize +
		       "\n  lock timeout: " + lockTimeout +
		       "\n  max retry: " + maxRetry +
		       "\n  object id batch size: " + objectIdBatchSize +
		       "\n  retry wait: " + retryWait +
		       "\n  server host: " + serverHost +
		       "\n  server port: " + serverPort +
		       "\n  size: " + cacheSize +
		       "\n  start server: " + startServer +
		       "\n  update queue size: " + updateQueueSize +
		       (checkBindings != CheckBindingsType.NONE
			? "\n  check bindings: " + checkBindings : ""));
	}
	try {
	    if (serverHost == null && startServer) {
		serverHost = InetAddress.getLocalHost().getHostName();
	    }
	    this.txnProxy = txnProxy;
	    taskOwner = txnProxy.getCurrentOwner();
	    txnScheduler =
		systemRegistry.getComponent(TransactionScheduler.class);
	    if (startServer) {
		try {
		    localServer = new CachingDataStoreServerImpl(
			properties, systemRegistry, txnProxy);
		    serverPort = localServer.getServerPort();
		    logger.log(INFO, "Started server: {0}", localServer);
		} catch (IOException t) {
		    logger.logThrow(SEVERE, t, "Problem starting server");
		    throw t;
		} catch (RuntimeException t) {
		    logger.logThrow(SEVERE, t, "Problem starting server");
		    throw t;
		}
	    } else {
		localServer = null;
	    }
	    server = lookupServer(serverHost, serverPort);
	    LoggingCallbackServer callbackServer =
		new LoggingCallbackServer(this, logger);
	    callbackExporter.export(callbackServer, callbackPort);
	    CallbackServer callbackProxy = callbackExporter.getProxy();
	    RegisterNodeResult registerNodeResult =
		registerNode(callbackProxy);
	    nodeId = registerNodeResult.nodeId;
	    callbackServer.setLocalNodeId(nodeId);
	    updateQueue = new UpdateQueue(
		this, serverHost, registerNodeResult.updateQueuePort,
		updateQueueSize);
	    contextMap = new TxnContextMap(this);
	    cache = new Cache(this, cacheSize, numLocks, evictionThread);
	    newObjectIdCache = new NewObjectIdCache(this, objectIdBatchSize);
	    evictionThread.start();
	    dataConflictThread.start();
	} catch (Exception e) {
	    shutdownInternal();
	    throw e;
	}
    }

    /**
     * Returns the server stored in the registry.
     *
     * @param	serverHost the server host
     * @param	serverPort the server port
     * @return	the server
     * @throws	IOException if there are too many I/O failures
     * @throws	NotBoundException if the server is not found in the registry
     */
    private CachingDataStoreServer lookupServer(
	String serverHost, int serverPort)
	throws IOException, NotBoundException
    {
	ShouldRetryIo retry = new ShouldRetryIo(maxRetry, retryWait);
	while (true) {
	    try {
		return (CachingDataStoreServer) LocateRegistry.getRegistry(
		    serverHost, serverPort).lookup("CachingDataStoreServer");
	    } catch (IOException e) {
		if (!retry.shouldRetry()) {
		    throw e;
		}
	    }
	}
    }

    /**
     * Registers this node.
     *
     * @param	callbackProxy the callback server for this node
     * @return	the results of registering this node
     * @throws	IOException if there are too many I/O failures
     */
    private RegisterNodeResult registerNode(CallbackServer callbackProxy)
	throws IOException
    {
	ShouldRetryIo retry = new ShouldRetryIo(maxRetry, retryWait);
	while (true) {
	    try {
		return server.registerNode(callbackProxy);
	    } catch (IOException e) {
		if (!retry.shouldRetry()) {
		    throw e;
		}
	    }
	}
    }

    /* -- Implement AbstractDataStore's DataStore methods -- */

    /**
     * {@inheritDoc}
     *
     * @throws	Exception {@inheritDoc}
     */
    @Override
    public void ready() throws Exception {
	synchronized (watchdogServiceSync) {
	    if (failureBeforeWatchdog != null) {
		if (failureBeforeWatchdog instanceof Error) {
		    throw (Error) failureBeforeWatchdog;
		} else {
		    throw (Exception) failureBeforeWatchdog;
		}
	    }
	    watchdogService = txnProxy.getService(WatchdogService.class);
	}
	if (localServer != null) {
	    localServer.ready();
	}
    }

    /* DataStore.getLocalNodeId */

    /** {@inheritDoc} */
    @Override
    protected long getLocalNodeIdInternal() {
	return nodeId;
    }

    /* DataStore.createObject */

    /** {@inheritDoc} */
    @Override
    protected long createObjectInternal(Transaction txn) {
	TxnContext context = contextMap.join(txn);
	long oid = newObjectIdCache.getNewObjectId();
	ReserveCache reserve = new ReserveCache(cache);
	try {
	    synchronized (cache.getObjectLock(oid)) {
		context.noteNewObject(oid, reserve);
	    }
	} finally {
	    reserve.done();
	}
	return oid;
    }

    /* DataStore.markForUpdate */

    /** {@inheritDoc} */
    @Override
    protected void markForUpdateInternal(Transaction txn, long oid) {
	TxnContext context = contextMap.join(txn);
	long stop = context.getStopTime();
	Object lock = cache.getObjectLock(oid);
	synchronized (lock) {
	    for (int i = 0; true; i++) {
		assert i < 1000 : "Too many retries";
		ObjectCacheEntry entry = cache.getObjectEntry(oid);
		assert entry != null :
		    "markForUpdate called for object not in cache";
		switch (entry.awaitWritable(lock, stop)) {
		case DECACHED:
		    continue;
		case READABLE:
		    /* Upgrade */
		    entry.setFetchingUpgrade(lock);
		    scheduleFetch(new UpgradeObjectRunnable(context, oid));
		    AwaitWritableResult result =
			entry.awaitWritable(lock, stop);
		    assert result == AwaitWritableResult.WRITABLE;
		    break;
		case WRITABLE:
		    /* Already cached for write */
		    break;
		default:
		    throw new AssertionError();
		}
		return;
	    }
	}
    }

    /** Upgrade an existing object. */
    private class UpgradeObjectRunnable extends RetryIoRunnable<Boolean> {
	private final TxnContext context;
	private final long oid;
	UpgradeObjectRunnable(TxnContext context, long oid) {
	    super(CachingDataStore.this);
	    this.context = context;
	    this.oid = oid;
	}
	@Override
	public String toString() {
	    return "UpgradeObjectRunnable[" +
		"context:" + context + ", oid:" + oid + "]";
	}
	Boolean callOnce() throws CacheConsistencyException, IOException {
	    return server.upgradeObject(nodeId, oid);
	}
	void runWithResult(Boolean callbackEvict) {
	    Object lock = cache.getObjectLock(oid);
	    synchronized (lock) {
		ObjectCacheEntry entry = cache.getObjectEntry(oid);
		context.noteAccess(entry);
		entry.setUpgraded(lock);
	    }
	    if (callbackEvict) {
		scheduleTask(new DowngradeObjectTask(oid));
	    }
	}
    }

    /* DataStore.getObject */

    /** {@inheritDoc} */
    @Override
    protected byte[] getObjectInternal(
	Transaction txn, long oid, boolean forUpdate)
    {
	TxnContext context = contextMap.join(txn);
	long stop = context.getStopTime();
	Object lock = cache.getObjectLock(oid);
	byte[] value;
	for (int i = 0; true; i++) {
	    assert i < 1000 : "Too many retries";
	    ReserveCache reserve = new ReserveCache(cache);
	    try {
		synchronized (lock) {
		    ObjectCacheEntry entry = cache.getObjectEntry(oid);
		    if (entry == null) {
			entry = context.noteFetchingObject(
			    oid, forUpdate, reserve);
			scheduleFetch(
			    forUpdate
			    ? new GetObjectForUpdateRunnable(context, oid)
			    : new GetObjectRunnable(context, oid));
		    }
		    if (!forUpdate) {
			if (!entry.awaitReadable(lock, stop)) {
			    continue;
			}
		    } else {
			switch (entry.awaitWritable(lock, stop)) {
			case DECACHED:
			    continue;
			case READABLE:
			    /* Upgrade */
			    entry.setFetchingUpgrade(lock);
			    scheduleFetch(
				new UpgradeObjectRunnable(context, oid));
			    AwaitWritableResult result =
				entry.awaitWritable(lock, stop);
			    assert result == AwaitWritableResult.WRITABLE;
			    break;
			case WRITABLE:
			    /* Already cached for write */
			    break;
			default:
			    throw new AssertionError();
			}
		    }
		    value = entry.getValue();
		    break;
		}
	    } finally {
		reserve.done();
	    }
	}
	if (value == null) {
	    throw new ObjectNotFoundException("Object not found: " + oid);
	}
	return value;
    }

    /** Gets an object for read. */
    private class GetObjectRunnable extends RetryIoRunnable<GetObjectResults> {
	private final TxnContext context;
	private final long oid;
	GetObjectRunnable(TxnContext context, long oid) {
	    super(CachingDataStore.this);
	    this.context = context;
	    this.oid = oid;
	}
	@Override
	public String toString() {
	    return "GetObjectRunnable[" +
		"context:" + context + ", oid:" + oid + "]";
	}
	GetObjectResults callOnce() throws IOException {
	    return server.getObject(nodeId, oid);
	}
	void runWithResult(GetObjectResults results) {
	    synchronized (cache.getObjectLock(oid)) {
		context.noteCachedObject(
		    cache.getObjectEntry(oid),
		    (results != null) ? results.data : null,
		    false);
	    }
	    if (results != null && results.callbackEvict) {
		scheduleTask(new EvictObjectTask(oid));
	    }
	}
    }

    /** Gets an object for write. */
    private class GetObjectForUpdateRunnable
	extends RetryIoRunnable<GetObjectForUpdateResults>
    {
	private final TxnContext context;
	private final long oid;
	GetObjectForUpdateRunnable(TxnContext context, long oid) {
	    super(CachingDataStore.this);
	    this.context = context;
	    this.oid = oid;
	}
	@Override
	public String toString() {
	    return "GetObjectForUpdateRunnable[" +
		"context:" + context + ", oid:" + oid + "]";
	}
	GetObjectForUpdateResults callOnce() throws IOException {
	    return server.getObjectForUpdate(nodeId, oid);
	}
	void runWithResult(GetObjectForUpdateResults results) {
	    synchronized (cache.getObjectLock(oid)) {
		context.noteCachedObject(
		    cache.getObjectEntry(oid),
		    (results != null) ? results.data : null,
		    true);
	    }
	    if (results != null) {
		if (results.callbackEvict) {
		    scheduleTask(new EvictObjectTask(oid));
		} else if (results.callbackDowngrade) {
		    scheduleTask(new DowngradeObjectTask(oid));
		}
	    }
	}
    }

    /* DataStore.setObject */

    /** {@inheritDoc} */
    @Override
    protected void setObjectInternal(Transaction txn, long oid, byte[] data) {
	TxnContext context = contextMap.join(txn);
	long stop = context.getStopTime();
	Object lock = cache.getObjectLock(oid);
	for (int i = 0; true; i++) {
	    ReserveCache reserve = new ReserveCache(cache);
	    try {
		synchronized (lock) {
		    assert i < 1000 : "Too many retries";
		    ObjectCacheEntry entry = cache.getObjectEntry(oid);
		    if (entry == null) {
			/* Fetch for write */
			entry = context.noteFetchingObject(oid, true, reserve);
			scheduleFetch(
			    new GetObjectForUpdateRunnable(context, oid));
		    }
		    switch (entry.awaitWritable(lock, stop)) {
		    case DECACHED:
			/* Not in cache -- try again */
			continue;
		    case READABLE:
			/* Upgrade */
			entry.setFetchingUpgrade(lock);
			scheduleFetch(new UpgradeObjectRunnable(context, oid));
			AwaitWritableResult result =
			    entry.awaitWritable(lock, stop);
			assert result == AwaitWritableResult.WRITABLE;
			break;
		    case WRITABLE:
			/* Already cached for write */
			break;
		    default:
			throw new AssertionError();
		    }
		    if (data == null && entry.getValue() == null) {
			/* Attempting to remove an already removed object */
			throw new ObjectNotFoundException(
			    "Object oid:" + oid + " was not found");
		    }
		    context.noteModifiedObject(entry, data);
		    break;
		}
	    } finally {
		reserve.done();
	    }
	}
    }

    /* DataStore.setObjects */

    /** {@inheritDoc} */
    @Override
    protected void setObjectsInternal(
	Transaction txn, long[] oids, byte[][] dataArray)
    {
	for (int i = 0; i < oids.length; i++) {
	    setObjectInternal(txn, oids[i], dataArray[i]);
	}
    }

    /* DataStore.removeObject */

    /** {@inheritDoc} */
    @Override
    protected void removeObjectInternal(Transaction txn, long oid) {
	setObjectInternal(txn, oid, null);
    }

    /* DataStore.getBinding */

    /**
     * {@inheritDoc} <p>
     *
     * The implementation needs to handle the following cases:
     * <ol class="outline">
     * <li> Entry for name found in cache <ul>
     *	 <li> Return cached value </ul>
     * <li> No entry for name found in cache <ol>
     *	 <li> Next entry records that name is unbound <ul>
     *	   <li> Return next name and that name is unbound </ul>
     *	 <li> Next entry does not cover name <ul>
     *	   <li> Mark next entry pending previous
     *	   <li> Call server <ol>
     *	     <li> Server returns name found <ul>
     *	       <li> Try again </ul>
     *	     <li> Server returns name not found <ol>
     *	       <li> Next entry records that name is unbound <ul>
     *		 <li> Return next name and that name is unbound </ul>
     *	       <li> Next entry does not cover name <ul>
     *		 <li> Try again </ul> </ol> </ol> </ul>
     *	 <li> No next entry <ul>
     *	   <li> Create last entry
     *	   <li> Mark last entry pending previous and fetching read
     *	   <li> Call server <ol>
     *	     <li> Server returns name found <ul>
     *		<li> Try again </ul>
     *	     <li> Server returns name not found <ol>
     *		<li> Next entry records that name is unbound <ul>
     *		  <li> Return next name and that name is unbound </ul>
     *		<li> Next entry does not cover name <ul>
     *		  <li> Try again </ul> </ol> </ol> </ul> </ol> </ol>
     */
    @Override
    protected BindingValue getBindingInternal(Transaction txn, String name) {
	TxnContext context = contextMap.join(txn);
	long stop = context.getStopTime();
	BindingKey nameKey = BindingKey.get(name);
	BindingValue result;
	for (int i = 0; true; i++) {
	    assert i < 1000 : "Too many retries";
	    /* Find cache entry for name or next higher name */
	    BindingCacheEntry entry = cache.getCeilingBindingEntry(nameKey);
	    Object lock =
		cache.getBindingLock((entry != null) ? entry.key : LAST);
	    ReserveCache reserve = new ReserveCache(cache, 2);
	    try {
		synchronized (lock) {
		    if (logger.isLoggable(FINEST)) {
			logger.log(FINEST,
				   "getBindingInternal txn:" + txn +
				   ", name:" + name + " found entry:" + entry);
		    }
		    if (entry == null) {
			/* No next entry -- create it */
			entry = context.noteLastBinding(reserve);
			if (entry == null) {
			    /* Entry already present -- try again */
			    continue;
			}
			/* Else fall through to fetch from server */
		    } else if (!entry.awaitReadable(lock, stop)) {
			/* The entry is not in the cache -- try again */
			continue;
		    } else if (nameKey.equals(entry.key)) {
			/* Name is bound */
			context.noteAccess(entry);
			result = new BindingValue(entry.getValue(), null);
			break;
		    } else if (!assureNextEntry(entry, nameKey, lock, stop)) {
			/* Entry is no longer for next name -- try again */
			continue;
		    } else if (entry.getKnownUnbound(nameKey)) {
			/* Name is unbound */
			context.noteAccess(entry);
			result =
			    new BindingValue(-1, entry.key.getNameAllowLast());
			break;
		    }
		    /* Get information from server about this name */
		    entry.setPendingPrevious();
		    scheduleFetch(
			new GetBindingRunnable(
			    context, nameKey, entry.key, reserve));
		    continue;
		}
	    } finally {
		reserve.done();
	    }
	}
	maybeCheckBindings(CheckBindingsType.OPERATION);
	return result;
    }

    /**
     * Make sure that the entry is not the next entry for a pending operation,
     * is not being upgraded, and that it is indeed the next entry in the cache
     * after the specified key.
     *
     * @param	entry the entry
     * @param	previousKey the key for which {@code entry} should be the next
     *		entry in the cache
     * @param	lock the object to use when waiting for notifications
     * @param	stop the time in milliseconds when waiting should fail
     * @return	{@code true} if {@code entry} is the next entry present in the
     *		cache after {@code previousKey}, else {@code false}
     * @throws	TransactionTimeoutException if the operation does not succeed
     *		before the specified stop time
     */
    private boolean assureNextEntry(BindingCacheEntry entry,
				    BindingKey previousKey,
				    Object lock,
				    long stop)
    {
	assert Thread.holdsLock(lock);
	if (entry.getUpgrading()) {
	    entry.awaitNotUpgrading(lock, stop);
	}
	entry.awaitNotPendingPrevious(lock, stop);
	BindingCacheEntry checkEntry =
	    cache.getHigherBindingEntry(previousKey);
	if (checkEntry != entry) {
	    /*
	     * Another entry was inserted in the time between when we got this
	     * entry and when we locked it -- try again
	     */
	    return false;
	} else if (entry.getUpgrading()) {
	    /* The entry is again being upgraded -- try again */
	    return false;
	} else {
	    /* Check that entry is readable or being read */
	    return entry.getReadable() || entry.getReading();
	}
    }

    /**
     * A {@link Runnable} that calls {@code getBinding} on the server to get
     * information about a requested name for which there were no entries
     * cached.  The caller should have reserved 1 cache entry.  The entry for
     * {@code cachedNextNameKey} is marked pending previous.  If it is the last
     * entry, it is also marked fetching read if it was added to represent the
     * next entry provisionally.  The entry will not be pending previous or
     * fetching when the operation is complete.
     */
    private class GetBindingRunnable
	extends BasicBindingRunnable<GetBindingResults>
    {
	GetBindingRunnable(TxnContext context,
			   BindingKey nameKey,
			   BindingKey cachedNextNameKey,
			   ReserveCache reserve)
	{
	    super(context, nameKey, cachedNextNameKey, reserve);
	}
	@Override
	public String toString() {
	    return "GetBindingRunnable[" +
		"context:" + context +
		", nameKey:" + nameKey +
		", cachedNextNameKey:" + cachedNextNameKey +
		"]";
	}
	GetBindingResults callOnce() throws IOException {
	    return server.getBinding(nodeId, nameKey.getName());
	}
	void runWithResult(GetBindingResults results) {
	    BindingKey serverNextNameKey = (results.found)
		? null : BindingKey.getAllowLast(results.nextName);
	    handleResults(results.found ? BOUND : UNBOUND, results.oid,
			  /* nameForWrite */ false,
			  serverNextNameKey, results.oid,
			  /* nextForWrite */ false);
	    /* Schedule eviction */
	    if (results.callbackEvict) {
		scheduleTask(
		    new EvictBindingTask(
			results.found ? nameKey : serverNextNameKey));
	    }
	}
    }

    /**
     * A {@code ReserveCacheRetryIoRunnable} that provides utility methods for
     * handling the results of server binding calls.
     */
    private abstract class BasicBindingRunnable<V>
	extends ReserveCacheRetryIoRunnable<V>
    {
	/** The transaction context. */
	final TxnContext context;

	/** The key for the requested name. */
	final BindingKey nameKey;

	/** The key for the next cached name. */
	final BindingKey cachedNextNameKey;

	/**
	 * Creates an instance with one preallocated entry.
	 *
	 * @param	context the transaction context
	 * @param	nameKey the key for the requested name
	 * @param	cachedNextNameKey the key for the next cached name
	 * @param	reserve for tracking cache reservations
	 */
	BasicBindingRunnable(TxnContext context,
			     BindingKey nameKey,
			     BindingKey cachedNextNameKey,
			     ReserveCache reserve)
	{
	    this(context, nameKey, cachedNextNameKey, reserve, 1);
	}

	/**
	 * Creates an instance with the specified number of preallocated
	 * entries.
	 *
	 * @param	context the transaction context
	 * @param	nameKey the key for the requested name
	 * @param	cachedNextNameKey the key for the next cached name
	 * @param	reserve for tracking cache reservations
	 * @param	numCacheEntries the number of entries to preallocate in
	 *		the cache
	 */
	BasicBindingRunnable(TxnContext context,
			     BindingKey nameKey,
			     BindingKey cachedNextNameKey,
			     ReserveCache reserve,
			     int numCacheEntries)
	{
	    super(reserve, numCacheEntries);
	    this.context = context;
	    this.nameKey = nameKey;
	    this.cachedNextNameKey = cachedNextNameKey;
	}

	/**
	 * Handles the results of a server binding call.  This method uses 2
	 * cache entries entries for both the requested and next names are not
	 * found.  When called, the entry for {@code nameKey} should be marked
	 * fetching upgrade if {@code nameForWrite} is {@code true} and the
	 * entry is not writable.  Also, the entry for {@code
	 * cachedNextNameKey} will be marked pending previous.  If the next
	 * entry is the last entry, it is also marked fetching read if it was
	 * added to represent the next entry provisionally. The next entry will
	 * not be pending previous, and neither entry will be fetching, when
	 * the method returns.
	 *
	 * @param	nameBound the binding state of the name
	 * @param	nameOid the value associated with the name, if bound
	 * @param	nameForWrite whether to cache the name for write
	 * @param	serverNextNameKey the key for the next name found on
	 *		the server, or {@code null} if not supplied
	 * @param	serverNextNameOid the object ID associated with the
	 *		next name found
	 * @param	serverNextNameForWrite whether to cache the next name
	 *		found for write
	 */
	void handleResults(BindingState nameBound,
			   long nameOid,
			   boolean nameForWrite,
			   BindingKey serverNextNameKey,
			   long serverNextNameOid,
			   boolean serverNextNameForWrite)
	{
	    updateNameEntry(nameBound, nameOid, nameForWrite);
	    updateServerNextEntry(nameBound, serverNextNameKey,
				  serverNextNameOid, serverNextNameForWrite);
	    updateCachedNextEntry(nameBound, serverNextNameKey,
				  serverNextNameForWrite);
	}

	/**
	 * Updates the entry for the requested name given the results of a
	 * server binding call.  This method uses 1 cache entry if an entry for
	 * the name is not found.
	 *
	 * @param	nameBound the binding state of the name
	 * @param	nameOid the value associated with the name, if bound
	 * @param	nameForWrite whether to cache the name for write
	 */
	private void updateNameEntry(
	    BindingState nameBound, long nameOid, boolean nameForWrite)
	{
	    if (nameBound == BOUND) {
		Object lock = cache.getBindingLock(nameKey);
		synchronized (lock) {
		    BindingCacheEntry entry = cache.getBindingEntry(nameKey);
		    if (entry == null) {
			context.noteCachedBinding(
			    nameKey, nameOid, nameForWrite, reserve);
		    } else {
			context.noteAccess(entry);
			if (nameForWrite && !entry.getWritable()) {
			    assert !entry.getPendingPrevious();
			    entry.setUpgraded(lock);
			} else {
			    assert !entry.getUpgrading();
			}
		    }
		}
	    }
	}

	/**
	 * Updates the entry for the next name returned by the server.  This
	 * method uses 1 cache entry if an entry for the next name is not
	 * found.
	 *
	 * @param	nameBound the binding state of the name
	 * @param	serverNextNameKey the key for the next name found on
	 *		the server, or {@code null} if not supplied
	 * @param	serverNextNameOid the object ID associated with the
	 *		next name found
	 * @param	serverNextNameForWrite whether to cache the next name
	 *		found for write
	 */
	private void updateServerNextEntry(BindingState nameBound,
					   BindingKey serverNextNameKey,
					   long serverNextNameOid,
					   boolean serverNextNameForWrite)
	{
	    int compareServer = (serverNextNameKey != null)
		? serverNextNameKey.compareTo(cachedNextNameKey) : 0;
	    if (compareServer != 0) {
		/*
		 * Update the entry for the next name from server if it is
		 * different from the cached next name.	 Only need to do
		 * something if the server entry is lower than the cached next
		 * entry, in which case the entry should need to be created.
		 * If the server entry is higher, then it should already be
		 * present in the cache.
		 */
		Object lock = cache.getBindingLock(serverNextNameKey);
		synchronized (lock) {
		    BindingCacheEntry entry =
			cache.getBindingEntry(serverNextNameKey);
		    /*
		     * There should be no entry for the server's next name
		     * precisely when the server's next name is lower than the
		     * cached one
		     */
		    assert (entry == null) == (compareServer < 0);
		    if (entry == null) {
			/*
			 * Add an entry for next name from server, which is the
			 * next entry after the requested name
			 */
			entry = context.noteCachedBinding(
			    serverNextNameKey, serverNextNameOid,
			    serverNextNameForWrite, reserve);
			entry.updatePreviousKey(nameKey, nameBound);
		    }
		}
	    }
	}

	/**
	 * Updates the entry for the cached next name given the results of a
	 * server binding call.
	 *
	 * @param	nameBound the binding state of the name
	 * @param	serverNextNameKey the key for the next name found on
	 *		the server, or {@code null} if not supplied
	 * @param	serverNextNameForWrite whether to cache the next name
	 *		found for write
	 */
	private void updateCachedNextEntry(BindingState nameBound,
					   BindingKey serverNextNameKey,
					   boolean serverNextNameForWrite)
	{
	    Object lock = cache.getBindingLock(cachedNextNameKey);
	    synchronized (lock) {
		BindingCacheEntry entry =
		    cache.getBindingEntry(cachedNextNameKey);
		assert entry != null : "No entry for " + cachedNextNameKey;
		if (serverNextNameKey != null &&
		    serverNextNameKey.compareTo(cachedNextNameKey) >= 0)
		{
		    /*
		     * The server returned information about the next key, and
		     * there were no entries between the name and the cached
		     * next entry
		     */
		    boolean updated =
			entry.updatePreviousKey(nameKey, nameBound);
		    assert updated;
		    context.noteAccess(entry);
		    if (entry.getReading()) {
			/* Make a temporary last entry permanent */
			assert cachedNextNameKey == LAST;
			entry.setCachedRead(lock);
		    }
		    if (serverNextNameForWrite && !entry.getWritable()) {
			/* Upgraded to write access for the next key */
			entry.setUpgradedImmediate(lock);
		    }
		} else if (entry.getReading()) {
		    /*
		     * Remove or downgrade a temporary last entry that was not
		     * used
		     */
		    assert cachedNextNameKey == LAST;
		    entry.setEvictedAbandonFetching(lock);
		    cache.removeBindingEntry(cachedNextNameKey);
		}
		entry.setNotPendingPrevious(lock);
	    }
	}
    }

    /**
     * A {@code RetryIoRunnable} that tracks space in the cache and releases
     * any space that it does not use.  Note callers should have already
     * allocated the needed cache space.
     */
    private abstract class ReserveCacheRetryIoRunnable<V>
	extends RetryIoRunnable<V>
    {
	/** Tracks cache space. */
	final ReserveCache reserve;

	/**
	 * Creates an instance with one preallocated entry.
	 *
	 * @param	reserve for tracking cache reservations
	 */
	ReserveCacheRetryIoRunnable(ReserveCache reserve) {
	    this(reserve, 1);
	}

	/**
	 * Creates an instance with the specified number of preallocated
	 * entries.
	 *
	 * @param	reserve for tracking cache reservations
	 * @param	numCacheEntries the number of entries to preallocate in
	 *		the cache
	 * @throws	IllegalArgumentException if {@code numCacheEntries} is
	 *		less than {@code 1}
	 */
	ReserveCacheRetryIoRunnable(
	    ReserveCache reserve, int numCacheEntries)
	{
	    super(CachingDataStore.this);
	    this.reserve = new ReserveCache(cache, numCacheEntries);
	    reserve.used(numCacheEntries);
	}

	/**
	 * Calls the superclass's {@code run} method, but arranging to release
	 * any unused cache entries when returning.
	 */
	public void run() {
	    try {
		super.run();
	    } finally {
		reserve.done();
	    }
	}
    }

    /* DataStore.setBinding */

    /**
     * {@inheritDoc} <p>
     *
     * The implementation needs to handle the following cases:
     * <ol class="outline">
     * <li> Entry for name found in cache <ol>
     *	 <li> Entry is cached for write <ul>
     *	   <li> Store new value and return that name was already bound </ul>
     *	 <li> Entry is cached for read <ul>
     *	   <li> Mark entry as fetching upgrade
     *	   <li> Call server
     *	   <li> Store new value and return that name was already bound </ul>
     *		</ol>
     * <li> No entry for name found in cache <ol>
     *	 <li> Next entry records that name is unbound <ol>
     *	   <li> Next entry is cached for write <ul>
     *	     <li> Mark next entry pending previous
     *	     <li> Create new entry for name and value
     *	     <li> Report write access to next name
     *	     <li> Store in next entry that name is bound
     *	     <li> Mark next entry not pending previous
     *	     <li> Return that name was unbound </ul>
     *	   <li> Next entry is cached for read <ul>
     *	     <li> Mark next entry pending previous
     *	     <li> Call server
     *	     <li> Mark next entry pending previous
     *	     <li> Create new entry for name and value
     *	     <li> Report write access to next name
     *	     <li> Store in next entry that name is bound
     *	     <li> Return that name was unbound </ul> </ol>
     *	 <li> Next entry does not cover name <ul>
     *	   <li> Mark next entry pending previous
     *	   <li> Call server
     *	   <li> Try again </ul>
     *	 <li> No next entry <ul>
     *	   <li> Create a last entry
     *	   <li> Mark last entry pending previous and fetching read
     *	   <li> Call server
     *	   <li> Try again </ul> </ol> </ol>
     */
    @Override
    protected BindingValue setBindingInternal(
	Transaction txn, String name, long oid)
    {
	TxnContext context = contextMap.join(txn);
	long stop = context.getStopTime();
	BindingKey nameKey = BindingKey.get(name);
	BindingValue result;
	for (int i = 0; true; i++) {
	    assert i < 1000 : "Too many retries";
	    /* Find cache entry for name or next higher name */
	    BindingCacheEntry entry = cache.getCeilingBindingEntry(nameKey);
	    final BindingKey entryKey = (entry != null) ? entry.key : LAST;
	    final Object lock = cache.getBindingLock(entryKey);
	    ReserveCache reserve = new ReserveCache(cache, 2);
	    try {
		synchronized (lock) {
		    if (logger.isLoggable(FINEST)) {
			logger.log(FINEST,
				   "setBindingInternal txn:" + txn +
				   ", name:" + name + " found entry:" + entry);
		    }
		    if (entry == null) {
			/* No next entry -- create it */
			entry = context.noteLastBinding(reserve);
			if (entry == null) {
			    /* Entry already present -- try again */
			    continue;
			}
			setBindingInternalUnknown(
			    context, lock, entry, nameKey, reserve);
			continue;
		    } else if (nameKey.equals(entryKey)) {
			/* Found entry for name */
			if (!setBindingInternalFound(context, lock, entry)) {
			    /* Entry is not in cache -- try again */
			    continue;
			}
			/* Entry is writable */
			context.noteModifiedBinding(entry, oid);
			result = new BindingValue(1, null);
			break;
		    } else if (!assureNextEntry(entry, nameKey, lock, stop)) {
			/*
			 * The entry is not in the cache or is no longer the
			 * next entry -- try again
			 */
			continue;
		    } else if (entry.getKnownUnbound(nameKey)) {
			/* Found next entry and know name is unbound */
			if (!setBindingInternalUnbound(
				context, lock, entry, nameKey))
			{
			    /*
			     * Things changed while trying to get writable next
			     * entry -- try again
			     */
			    continue;
			}
			/*
			 * Next entry is writable and name is still known to be
			 * unbound -- fall through to create entry for the new
			 * binding
			 */
		    } else {
			/* Need to get information about name and try again */
			setBindingInternalUnknown(
			    context, lock, entry, nameKey, reserve);
			continue;
		    }
		}
	    } finally {
		reserve.done();
	    }
	    /* Get access coordinator lock for the next entry */
	    reportNameAccess(txnProxy.getCurrentTransaction(),
			     entryKey.getNameAllowLast(), WRITE);
	    /* Verify the next entry and mark it pending previous */
	    BindingKey entryPreviousKey;
	    boolean entryPreviousKeyUnbound;
	    synchronized (lock) {
		entry = cache.getBindingEntry(entryKey);
		if (entry == null ||
		    !assureNextEntry(entry, nameKey, lock, stop))
		{
		    continue;
		}
		entry.setPendingPrevious();
		entryPreviousKey = entry.getPreviousKey();
		entryPreviousKeyUnbound = entry.getPreviousKeyUnbound();
	    }
	    reserve = new ReserveCache(cache);
	    try {
		/* Create a new entry for the requested name */
		synchronized (cache.getBindingLock(nameKey)) {
		    BindingCacheEntry nameEntry =
			context.noteCachedBinding(nameKey, -1, true, reserve);
		    context.noteModifiedBinding(nameEntry, oid);
		    if (entryPreviousKey.compareTo(nameKey) < 0) {
			nameEntry.setPreviousKey(
			    entryPreviousKey, entryPreviousKeyUnbound);
		    }
		}
	    } finally {
		reserve.done();
	    }
	    synchronized (lock) {
		entry = cache.getBindingEntry(entryKey);
		assert entry != null : "No entry for " + entryKey;
		entry.setNotPendingPrevious(lock);
		context.noteModifiedBinding(entry, entry.getValue());
		entry.updatePreviousKey(nameKey, BOUND);
	    }
	    /* Name was unbound */
	    result = new BindingValue(-1, entryKey.getNameAllowLast());
	    break;
	}
	maybeCheckBindings(CheckBindingsType.OPERATION);
	return result;
    }

    /**
     * Implement {@code setBinding} for when an entry for the binding was found
     * in the cache, but needs to be checked for being writable.
     *
     * @param	context the transaction info
     * @param	lock the lock for the name entry
     * @param	entry the name entry
     * @return	{@code true} if the entry was found and is now writable,
     *		{@code false} if it was no longer in the cache
     */
    private boolean setBindingInternalFound(
	TxnContext context, Object lock, BindingCacheEntry entry)
    {
	assert Thread.holdsLock(lock);
	long stop = context.getStopTime();
	switch (entry.awaitWritable(lock, stop)) {
	case DECACHED:
	    /* Entry not in cache -- try again */
	    return false;
	case READABLE:
	    /*
	     * We've obtained a write lock from the access coordinator, so
	     * there can't be any evictions initiated.	For that reason, there
	     * is no need to retry once we confirm that the entry is cached.
	     */
	    entry.awaitNotPendingPrevious(lock, stop);
	    if (!entry.getWritable()) {
		/* Upgrade */
		entry.setFetchingUpgrade(lock);
		scheduleFetch(
		    new GetBindingForUpdateUpgradeRunnable(
			context, entry.key));
		return false;
	    }
	    return true;
	case WRITABLE:
	    /* Already writable */
	    return true;
	default:
	    throw new AssertionError();
	}
    }

    /**
     * A {@link Runnable} that calls {@code getBindingForUpgrade} on the server
     * to upgrade the read-only cache entry already present for the requested
     * name.  The entry for {@code nameKey} is marked fetching upgrade, and
     * will not be fetching when the operation is complete.
     */
    private class GetBindingForUpdateUpgradeRunnable
	extends RetryIoRunnable<GetBindingForUpdateResults>
    {
	private final TxnContext context;
	private final BindingKey nameKey;
	GetBindingForUpdateUpgradeRunnable(TxnContext context,
					   BindingKey nameKey)
	{
	    super(CachingDataStore.this);
	    this.context = context;
	    this.nameKey = nameKey;
	}
	@Override
	public String toString() {
	    return "GetBindingForUpdateUpgradeRunnable[" +
		"context:" + context +
		", nameKey:" + nameKey +
		"]";
	}
	GetBindingForUpdateResults callOnce() throws IOException {
	    return server.getBindingForUpdate(nodeId, nameKey.getName());
	}
	void runWithResult(GetBindingForUpdateResults results) {
	    assert results.found;
	    Object lock = cache.getBindingLock(nameKey);
	    synchronized (lock) {
		BindingCacheEntry entry = cache.getBindingEntry(nameKey);
		assert !entry.getPendingPrevious();
		context.noteAccess(entry);
		entry.setUpgraded(lock);
	    }
	    if (results.callbackEvict) {
		scheduleTask(new EvictBindingTask(nameKey));
	    } else if (results.callbackDowngrade) {
		scheduleTask(new DowngradeBindingTask(nameKey));
	    }
	}
    }

    /**
     * Implement {@code setBinding} for when an entry for next binding was
     * found and has cached that the requested name is unbound, but needs to be
     * checked for being writable.
     *
     * @param	context the transaction info
     * @param	lock the lock for the next entry
     * @param	entry the next entry
     * @param	nameKey the key for the requested name
     * @return	{@code true} if the next entry still caches that the requested
     *		name is unbound, or {@code false} if the information is no
     *		longer cached
     */
    private boolean setBindingInternalUnbound(TxnContext context,
					      Object lock,
					      BindingCacheEntry entry,
					      BindingKey nameKey)
    {
	long stop = context.getStopTime();
	switch (entry.awaitWritable(lock, stop)) {
	case DECACHED:
	    /* Entry not in cache -- try again */
	    return false;
	case READABLE:
	    entry.awaitNotPendingPrevious(lock, stop);
	    if (!entry.getKnownUnbound(nameKey)) {
		/* Operation on previous key changed things */
		return false;
	    }
	    if (!entry.getWritable()) {
		/* Upgrade */
		entry.setPendingPrevious();
		scheduleFetch(
		    new GetBindingForUpdateUpgradeNextRunnable(
			nameKey.getName(), entry.key));
		return false;
	    }
	    return entry.getKnownUnbound(nameKey);
	case WRITABLE:
	    /* Already writable */
	    return true;
	default:
	    throw new AssertionError();
	}
    }

    /**
     * A {@link Runnable} that calls {@code getBindingForUpdate} on the server
     * to upgrade the read-only cache entry already present for the next name
     * after the requested name, and that caches that the requested name is
     * unbound.  The entry for {@code nextNameKey} is marked pending previous
     * and will not be pending previous when the operation is complete.
     */
    private class GetBindingForUpdateUpgradeNextRunnable
	extends RetryIoRunnable<GetBindingForUpdateResults>
    {
	private final String name;
	private final BindingKey nextNameKey;
	GetBindingForUpdateUpgradeNextRunnable(
	    String name, BindingKey nextNameKey)
	{
	    super(CachingDataStore.this);
	    this.name = name;
	    this.nextNameKey = nextNameKey;
	}
	@Override
	public String toString() {
	    return "GetBindingForUpdateUpgradeNextRunnable[" +
		"name:" + name + ", nextNameKey:" + nextNameKey + "]";
	}
	GetBindingForUpdateResults callOnce() throws IOException {
	    return server.getBindingForUpdate(nodeId, name);
	}
	void runWithResult(GetBindingForUpdateResults results) {
	    assert !results.found;
	    Object lock = cache.getBindingLock(nextNameKey);
	    synchronized (lock) {
		BindingCacheEntry entry = cache.getBindingEntry(nextNameKey);
		assert nextNameKey.equals(
		    BindingKey.getAllowLast(results.nextName));
		entry.setUpgradedImmediate(lock);
		entry.setNotPendingPrevious(lock);
	    }
	    if (results.callbackEvict) {
		scheduleTask(new EvictBindingTask(nextNameKey));
	    } else if (results.callbackDowngrade) {
		scheduleTask(new DowngradeBindingTask(nextNameKey));
	    }
	}
    }

    /**
     * Implement {@code setBinding} for when no cache entries were found for
     * the requested name and the information needs to be requested from the
     * server.  The caller should already have checked that {@code entry} is
     * the next entry in the cache.  This method uses 2 cache entries.
     *
     * @param	context the transaction info
     * @param	lock the lock for the next entry
     * @param	nextEntry the next entry found in the cache
     * @param	nameKey the key for the requested name
     * @param	reserve for tracking cache reservations
     */
    private void setBindingInternalUnknown(TxnContext context,
					   Object lock,
					   BindingCacheEntry nextEntry,
					   BindingKey nameKey,
					   ReserveCache reserve)
    {
	/* Get information about the name */
	nextEntry.setPendingPrevious();
	scheduleFetch(
	    new GetBindingForUpdateRunnable(
		context, nameKey, nextEntry.key, reserve));
    }

    /**
     * A {@link Runnable} that calls {@code getBindingForUpdate} on the server
     * to get information about a requested name for which there were no
     * entries cached.  The caller should have reserved 2 cache entries.  The
     * entry for {@code cachedNextNameKey} should be marked pending previous.
     * If it is the last entry, it should also be marked fetching read if it
     * was added to represent the last entry provisionally.  The entry will not
     * be pending previous or fetching when the operation is complete.
     */
    private class GetBindingForUpdateRunnable
	extends BasicBindingRunnable<GetBindingForUpdateResults>
    {
	GetBindingForUpdateRunnable(TxnContext context,
				    BindingKey nameKey,
				    BindingKey cachedNextNameKey,
				    ReserveCache reserve)
	{
	    super(context, nameKey, cachedNextNameKey, reserve);
	}
	@Override
	public String toString() {
	    return "GetBindingForUpdateRunnable[" +
		"context:" + context +
		", nameKey:" + nameKey +
		", cachedNextNameKey:" + cachedNextNameKey +
		"]";
	}
	GetBindingForUpdateResults callOnce() throws IOException {
	    return server.getBindingForUpdate(nodeId, nameKey.getName());
	}
	void runWithResult(GetBindingForUpdateResults results) {
	    BindingKey serverNextNameKey = (results.found)
		? null : BindingKey.getAllowLast(results.nextName);
	    handleResults(results.found ? BOUND : UNBOUND, results.oid,
			  /* nameForWrite */ true,
			  serverNextNameKey, results.oid,
			  /* nextForWrite */ true);
	    /* Schedule eviction and downgrade */
	    BindingKey evictKey = results.found ? nameKey : serverNextNameKey;
	    if (results.callbackEvict) {
		scheduleTask(new EvictBindingTask(evictKey));
	    } else if (results.callbackDowngrade) {
		scheduleTask(new DowngradeBindingTask(evictKey));
	    }
	}
    }

    /* DataStore.removeBinding */

    /**
     * {@inheritDoc} <p>
     *
     * The implementation needs to handle the following cases:
     * <ol class="outline">
     * <li> Entry for name found in cache <ul>
     *	 <li> Check entry cache state <ol>
     *	   <li> Entry is cached for write
     *	   <li> Entry is cached for read <ul>
     *	     <li> Mark entry fetching upgrade </ul> </ol>
     *	 <li> Check next entry <ol>
     *	   <li> Next entry records that it is the next entry <ol>
     *	     <li> Next entry is cached for write <ul>
     *	       <li> Mark next entry pending previous
     *	       <li> Remove entry for name
     *	       <li> Report write access to next name
     *	       <li> Store in next entry that name is unbound
     *	       <li> Mark next entry not pending previous
     *	       <li> Return that name was bound </ul>
     *	     <li> Next entry is cached for read <ul>
     *	       <li> Mark next entry fetching upgrade
     *	       <li> Call server
     *	       <li> Mark next entry pending previous
     *	       <li> Remove entry for name
     *	       <li> Report write access to next name
     *	       <li> Store in next entry that name is unbound
     *	       <li> Mark next entry not pending previous
     *	       <li> Return that name was bound </ul> </ol>
     *	   <li> Next entry does not cover name <ul>
     *	     <li> Mark next entry pending previous
     *	     <li> Call server
     *	     <li> Try again </ul>
     *	   <li> No next entry <ul>
     *	     <li> Create a last entry
     *	     <li> Mark last entry pending previous and fetching read
     *	     <li> Call server
     *	     <li> Try again </ul> </ol> </ul>
     * <li> No entry for name found in cache <ol>
     *	<li> Next entry records that name is unbound <ul>
     *	  <li> Return that name was already unbound </ul>
     *	<li> Next entry does not cover name <ul>
     *	  <li> Mark next entry pending previous
     *	  <li> Call server
     *	  <li> Try again </ul>
     *	<li> No next entry <ul>
     *	  <li> Create a last entry
     *	  <li> Mark last entry pending previous and fetching read
     *	  <li> Call server
     *	  <li> Try again </ul> </ol> </ol>
     */
    @Override
    protected BindingValue removeBindingInternal(
	Transaction txn, String name)
    {
	TxnContext context = contextMap.join(txn);
	long stop = context.getStopTime();
	BindingKey nameKey = BindingKey.get(name);
	BindingValue result;
	for (int i = 0; true; i++) {
	    assert i < 1000 : "Too many retries";
	    /* Find cache entry for name or next higher name */
	    BindingCacheEntry entry = cache.getCeilingBindingEntry(nameKey);
	    Object lock =
		cache.getBindingLock((entry != null) ? entry.key : LAST);
	    boolean nameWritable;
	    ReserveCache reserve = new ReserveCache(cache, 3);
	    try {
		synchronized (lock) {
		    if (logger.isLoggable(FINEST)) {
			logger.log(FINEST,
				   "removeBindingInternal txn:" + txn +
				   ", name:" + name + " found entry:" + entry);
		    }
		    if (entry == null) {
			/* No next entry -- create it */
			entry = context.noteLastBinding(reserve);
			if (entry == null) {
			    /* Entry already present -- try again */
			    continue;
			}
			removeBindingInternalCallServer(
			    context, lock, entry, nameKey, reserve);
			continue;
		    } else if (nameKey.equals(entry.key)) {
			/* Found entry for name */
			if (!removeBindingInternalFound(entry, lock, stop)) {
			    /* Entry is not in cache -- try again */
			    continue;
			}
			nameWritable = entry.getWritable();
			/* Fall through to work on next entry */
		    } else if (!assureNextEntry(entry, nameKey, lock, stop)) {
			/*
			 * The entry is not in the cache or is no longer the
			 * next entry -- try again
			 */
			continue;
		    } else if (entry.getKnownUnbound(nameKey)) {
			/* Found next entry and know name is unbound */
			context.noteAccess(entry);
			result =
			    new BindingValue(-1, entry.key.getNameAllowLast());
			break;
		    } else {
			/* Need to get information about name and try again */
			removeBindingInternalCallServer(
			    context, lock, entry, nameKey, reserve);
			continue;
		    }
		}
		/* Check next name */
		result = removeBindingInternalCheckNext(
		    context, nameKey, nameWritable);
		if (result != null) {
		    break;
		}
	    } finally {
		reserve.done();
	    }
	}
	maybeCheckBindings(CheckBindingsType.OPERATION);
	return result;
    }

    /**
     * Implement {@code removeBinding} for when an entry for the binding was
     * found in the cache.  Returns {@code false} if the entry is decached,
     * else returns {@code true}, and marks the entry for upgrading if it is
     * only readable.
     *
     * @param	entry the entry for the requested name
     * @param	lock the associated lock
     * @param	stop the time in milliseconds when waiting should fail
     * @return	{@code true} if the entry was found encached and not pending
     *		for a operation on the previous name
     */
    private boolean removeBindingInternalFound(
	BindingCacheEntry entry, Object lock, long stop)
    {
	assert Thread.holdsLock(lock);
	switch (entry.awaitWritable(lock, stop)) {
	case DECACHED:
	    /* Not in cache -- try again */
	    return false;
	case READABLE:
	    entry.awaitNotPendingPrevious(lock, stop);
	    if (entry.getReadable()) {
		/* Upgrade */
		entry.setFetchingUpgrade(lock);
		return true;
	    } else {
		/* Not in cache -- try again */
		return false;
	    }
	case WRITABLE:
	    /* Already writable */
	    return true;
	default:
	    throw new AssertionError();
	}
    }

    /**
     * Call {@code getBindingForRemove} on the server.  The caller should
     * already have checked that {@code entry} is the next entry in the cache.
     * This method uses 2 cache entries.
     *
     * @param	context the transaction info
     * @param	lock the lock for the next entry
     * @param	nextEntry the next entry found in the cache
     * @param	nameKey the key for the requested name
     * @param	reserve for tracking cache reservations
     */
    private void removeBindingInternalCallServer(TxnContext context,
						 Object lock,
						 BindingCacheEntry nextEntry,
						 BindingKey nameKey,
						 ReserveCache reserve)
    {
	/* Get information about the name */
	nextEntry.setPendingPrevious();
	scheduleFetch(
	    new GetBindingForRemoveRunnable(
		context, nameKey, nextEntry.key, reserve));
    }

    /**
     * A {@link Runnable} that calls {@code getBindingForRemove} on the server
     * to get information about the requested name and the next name.  The
     * caller should have reserved 2 cache entries.  The entry for {@code
     * nameKey}, if present, is marked fetching upgrade if it is not writable.
     * The entry for {@code cachedNextNameKey} is marked pending previous.  If
     * it is the last entry, it is also marked fetching read if it was added to
     * represent the next entry provisionally.  The next entry will not be
     * pending previous, and neither entry will be fetching, when the operation
     * is complete.
     */
    private class GetBindingForRemoveRunnable
	extends BasicBindingRunnable<GetBindingForRemoveResults>
    {
	GetBindingForRemoveRunnable(TxnContext context,
				    BindingKey nameKey,
				    BindingKey cachedNextNameKey,
				    ReserveCache reserve)
	{
	    super(context, nameKey, cachedNextNameKey, reserve, 2);
	}
	@Override
	public String toString() {
	    return "GetBindingForRemoveRunnable[" +
		"context:" + context +
		", nameKey:" + nameKey +
		", cachedNextNameKey:" + cachedNextNameKey +
		"]";
	}
	GetBindingForRemoveResults callOnce() throws IOException {
	    return server.getBindingForRemove(nodeId, nameKey.getName());
	}
	void runWithResult(GetBindingForRemoveResults results) {
	    BindingKey serverNextNameKey =
		BindingKey.getAllowLast(results.nextName);
	    handleResults(results.found ? BOUND : UNBOUND, results.oid,
			  /* nameForWrite */ true,
			  serverNextNameKey, results.nextOid,
			  /* nextForWrite */ results.found);
	    /* Schedule evictions and downgrades */
	    if (results.callbackEvict) {
		scheduleTask(new EvictBindingTask(nameKey));
	    } else if (results.callbackDowngrade) {
		scheduleTask(new DowngradeBindingTask(nameKey));
	    }
	    if (results.nextCallbackEvict) {
		scheduleTask(new EvictBindingTask(serverNextNameKey));
	    } else if (results.nextCallbackDowngrade) {
		scheduleTask(new DowngradeBindingTask(serverNextNameKey));
	    }
	}
    }

    /**
     * Implement {@code removeBinding} for when a cache entry was found for the
     * requested name and the next step is to find the entry for the next name.
     *
     * @param	context the transaction info
     * @param	nameKey the requested name
     * @param	nameValue the value of the requested name, which may be {@code
     *		-1} to indicate that the name was unbound
     * @param	nameWritable whether the name entry was writable
     * @return	information about the binding, if the entries for the name and
     *		the next name were found and were cached properly, else {@code
     *		null}
     */
    private BindingValue removeBindingInternalCheckNext(TxnContext context,
							BindingKey nameKey,
							boolean nameWritable)
    {
	long stop = context.getStopTime();
	BindingCacheEntry entry = cache.getHigherBindingEntry(nameKey);
	final BindingKey nextKey = (entry != null) ? entry.key : LAST;
	final Object lock = cache.getBindingLock(nextKey);
	ReserveCache reserve = new ReserveCache(cache, 3);
	try {
	    synchronized (lock) {
		if (logger.isLoggable(FINEST)) {
		    logger.log(FINEST,
			       "removeBindingInternal txn:" + context.txn +
			       ", name:" + nameKey.getName() +
			       " found next entry:" + entry);
		}
		if (entry == null) {
		    /* No next entry -- create it */
		    entry = context.noteLastBinding(reserve);
		    if (entry == null) {
			/* Entry already present -- try again */
			return null;
		    }
		    removeBindingInternalCallServer(
			context, lock, entry, nameKey, reserve);
		    return null;
		} else if (nameWritable &&
			   entry.getIsNextEntry(nameKey) &&
			   entry.getWritable())
		{
		    /* Both name and next name were writable -- fall through */
		} else if (!assureNextEntry(entry, nameKey, lock, stop)) {
		    /* Don't have the next entry -- try again */
		    return null;
		} else {
		    /* Need to get information about next name and try again */
		    removeBindingInternalCallServer(
			context, lock, entry, nameKey, reserve);
		    return null;
		}
	    }
	} finally {
	    reserve.done();
	}
	/* Get access coordinator lock for the next entry */
	reportNameAccess(txnProxy.getCurrentTransaction(),
			 nextKey.getNameAllowLast(), WRITE);
	/* Verify the next entry and mark it pending previous */
	synchronized (lock) {
	    entry = cache.getBindingEntry(nextKey);
	    if (entry == null ||
		!assureNextEntry(entry, nameKey, lock, stop) ||
		!entry.getWritable())
	    {
		return null;
	    }
	    entry.setPendingPrevious();
	}
	/* Update cache for remove */
	BindingKey previousKey;
	boolean previousKeyUnbound;
	synchronized (cache.getBindingLock(nameKey)) {
	    entry = cache.getBindingEntry(nameKey);
	    previousKey = entry.getPreviousKey();
	    previousKeyUnbound = entry.getPreviousKeyUnbound();
	    context.noteModifiedBinding(entry, -1);
	}
	synchronized (lock) {
	    entry = cache.getBindingEntry(nextKey);
	    context.noteModifiedBinding(entry, entry.getValue());
	    if (previousKey == null) {
		entry.updatePreviousKey(nameKey, UNBOUND);
	    } else {
		entry.updatePreviousKey(
		    previousKey, previousKeyUnbound ? UNBOUND : UNKNOWN);
	    }
	    entry.setNotPendingPrevious(lock);
	}
	return new BindingValue(1, nextKey.getNameAllowLast());
    }

    /* DataStore.nextBoundName */

    /**
     * {@inheritDoc} <p>
     *
     * The implementation needs to handle the following cases:
     * <ol class="outline">
     * <li> Next entry in cache records that it is the next entry <ul>
     *	 <li> Return next name </ul>
     * <li> Next entry does not cover name <ul>
     *	 <li> Mark next entry pending previous
     *	 <li> Call server <ol>
     *	   <li> Server returns name found <ul>
     *	     <li> Try again </ul>
     *	   <li> Server returns name not found <ol>
     *	     <li> Next entry records that it is the next entry <ul>
     *	       <li> Return next name </ul>
     *	     <li> Next entry does not cover name <ul>
     *	       <li> Try again </ul> </ol> </ol> </ul>
     * <li> No next entry <ul>
     *	 <li> Create last entry
     *	 <li> Mark last entry pending previous and fetching read
     *	 <li> Call server <ol>
     *	   <li> Server returns name found <ul>
     *	     <li> Try again </ul>
     *	   <li> Server returns name not found <ol>
     *	     <li> Next entry records that it is the next entry <ul>
     *	       <li> Return next name </ul>
     *	     <li> Next entry does not cover name <ul>
     *	       <li> Try again </ul> </ol> </ol> </ul> </ol>
     */
    @Override
    protected String nextBoundNameInternal(Transaction txn, String name) {
	TxnContext context = contextMap.join(txn);
	long stop = context.getStopTime();
	BindingKey nameKey = BindingKey.getAllowFirst(name);
	String result;
	for (int i = 0; true; i++) {
	    assert i < 1000 : "Too many retries";
	    /* Find next entry */
	    BindingCacheEntry entry = cache.getHigherBindingEntry(nameKey);
	    Object lock =
		cache.getBindingLock((entry != null) ? entry.key : LAST);
	    ReserveCache reserve = new ReserveCache(cache, 2);
	    try {
		synchronized (lock) {
		    if (logger.isLoggable(FINEST)) {
			logger.log(FINEST,
				   "nextBoundNameInternal txn:" + txn +
				   ", name:" + name + " found entry:" + entry);
		    }
		    if (entry == null) {
			/* No next entry -- create it */
			entry = context.noteLastBinding(reserve);
			if (entry == null) {
			    /* Entry already present -- try again */
			    continue;
			}
		    } else if (!assureNextEntry(entry, nameKey, lock, stop)) {
			/*
			 * The entry is not in the cache or is no longer the
			 * next entry
			 */
			continue;
		    } else if (entry.getIsNextEntry(nameKey)) {
			/* This is the next entry in the cache */
			context.noteAccess(entry);
			result = entry.key.getNameAllowLast();
			break;
		    }
		    /* Ask server for next bound name */
		    entry.setPendingPrevious();
		    scheduleFetch(
			new NextBoundNameRunnable(
			    context, nameKey, entry.key, reserve));
		    continue;
		}
	    } finally {
		reserve.done();
	    }
	}
	maybeCheckBindings(CheckBindingsType.OPERATION);
	return result;
    }

    /**
     * A {@link Runnable} that calls {@code nextBoundName} on the server to get
     * information about the next bound name after a specified name when that
     * information was not in the cache.  The caller should have reserved 1
     * cache entry.  The entry for {@code cachedNextNameKey} is marked pending
     * previous.  If it is the last entry, it is also marked fetching read if
     * it was added to represent the next entry provisionally.  The entry will
     * not be pending previous or fetching when the operation is complete.
     */
    private class NextBoundNameRunnable
	extends BasicBindingRunnable<NextBoundNameResults>
    {
	NextBoundNameRunnable(TxnContext context,
			      BindingKey nameKey,
			      BindingKey cachedNextNameKey,
			      ReserveCache reserve)
	{
	    super(context, nameKey, cachedNextNameKey, reserve);
	}
	@Override
	public String toString() {
	    return "NextBoundNameRunnable[" +
		"context:" + context +
		", nameKey:" + nameKey +
		", cachedNextNameKey:" + cachedNextNameKey +
		"]";
	}
	NextBoundNameResults callOnce() throws IOException {
	    return server.nextBoundName(nodeId, nameKey.getNameAllowFirst());
	}
	void runWithResult(NextBoundNameResults results) {
	    BindingKey serverNextNameKey =
		BindingKey.getAllowLast(results.nextName);
	    handleResults(/* nameBound */ null, -1,
			  /* nameForWrite */ false,
			  serverNextNameKey, results.oid,
			  /* nextForWrite */ false);
	    /* Schedule eviction */
	    if (results.callbackEvict) {
		scheduleTask(new EvictBindingTask(serverNextNameKey));
	    }
	}
    }

    /* Shutdown */

    /** {@inheritDoc} */
    @Override
    protected void shutdownInternal() {
	synchronized (shutdownSync) {
	    switch (shutdownState) {
	    case NOT_REQUESTED:
		shutdownState = ShutdownState.REQUESTED;
		break;
	    case REQUESTED:
	    case TXNS_COMPLETED:
		do {
		    try {
			shutdownSync.wait();
		    } catch (InterruptedException e) {
		    }
		} while (shutdownState != ShutdownState.COMPLETED);
		return;
	    case COMPLETED:
		return;
	    default:
		throw new AssertionError();
	    }
	}
	/* Prevent new transactions and wait for active ones to complete */
	if (contextMap != null) {
	    contextMap.shutdown();
	}
	/* Stop facilities used by active transactions */
	dataConflictThread.interrupt();
	evictionThread.interrupt();
	try {
	    dataConflictThread.join(10000);
	} catch (InterruptedException e) {
	}
	try {
	    evictionThread.join(10000);
	} catch (InterruptedException e) {
	}
	if (newObjectIdCache != null) {
	    newObjectIdCache.shutdown();
	}
	fetchExecutor.shutdownNow();
	try {
	    fetchExecutor.awaitTermination(10000, MILLISECONDS);
	} catch (InterruptedException e) {
	}
	/* Stop accepting callbacks */
	callbackExporter.unexport();
	/* Finish sending updates */
	if (updateQueue != null) {
	    updateQueue.shutdown();
	}
	/* Shut down server */
	if (localServer != null) {
	    localServer.shutdown();
	}
	/* Done */
	synchronized (shutdownSync) {
	    shutdownState = ShutdownState.COMPLETED;
	    shutdownSync.notifyAll();
	}
    }

    /* getClassId */

    /** {@inheritDoc} */
    @Override
    protected int getClassIdInternal(Transaction txn, byte[] classInfo) {
	contextMap.join(txn);
	try {
	    return server.getClassId(classInfo);
	} catch (IOException e) {
	    throw new NetworkException(e.getMessage(), e);
	}
    }

    /* getClassInfo */

    /** {@inheritDoc} */
    @Override
    protected byte[] getClassInfoInternal(Transaction txn, int classId)
	throws ClassInfoNotFoundException
    {
	contextMap.join(txn);
	try {
	    byte[] result = server.getClassInfo(classId);
	    if (result == null) {
		throw new ClassInfoNotFoundException(
		    "No information found for class ID " + classId);
	    }
	    return result;
	} catch (IOException e) {
	    throw new NetworkException(e.getMessage(), e);
	}
    }

    /* nextObjectId */

    /** {@inheritDoc} */
    @Override
    protected long nextObjectIdInternal(Transaction txn, long oid) {
	TxnContext context = contextMap.join(txn);
	long nextNew = context.nextNewObjectId(oid);
	long last = oid;
	for (int i = 0; true; i++) {
	    assert i < 1000 : "Too many retries";
	    NextObjectResults results;
	    try {
		results = server.nextObjectId(nodeId, last);
	    } catch (IOException e) {
		throw new NetworkException(e.getMessage(), e);
	    }
	    if (results != null && results.callbackEvict) {
		scheduleTask(new EvictObjectTask(results.oid));
	    }
	    if (results == null || (nextNew != -1 && results.oid > nextNew)) {
		/*
		 * Either no next on the server or the next is greater than the
		 * one allocated in this transaction
		 */
		return nextNew;
	    }
	    ReserveCache reserve = new ReserveCache(cache);
	    try {
		synchronized (cache.getObjectLock(results.oid)) {
		    ObjectCacheEntry entry = cache.getObjectEntry(results.oid);
		    if (entry == null) {
			/* No entry -- create it */
			context.noteCachedObject(
			    results.oid, results.data, reserve);
			return results.oid;
		    } else if (entry.getValue() != null) {
			/* Object was not removed */
			context.noteAccess(entry);
			return results.oid;
		    } else {
			/* Object was removed -- try again */
			last = results.oid;
		    }
		}
	    } finally {
		reserve.done();
	    }
	}
    }

    /** {@inheritDoc} */
    @Override
    protected void addDataConflictListenerInternal(
	DataConflictListener listener)
    {
	dataConflictListeners.add(listener);
    }

    /* -- Implement AbstractDataStore's TransactionParticipant methods -- */

    /** {@inheritDoc} */
    @Override
    protected boolean prepareInternal(Transaction txn) {
	return contextMap.prepare(txn);
    }

    /** {@inheritDoc} */
    @Override
    protected void prepareAndCommitInternal(Transaction txn) {
	contextMap.prepareAndCommit(txn);
	maybeCheckBindings(CheckBindingsType.TXN);
    }

    /** {@inheritDoc} */
    @Override
    protected void commitInternal(Transaction txn) {
	contextMap.commit(txn);
	maybeCheckBindings(CheckBindingsType.TXN);
    }

    /** {@inheritDoc} */
    @Override
    protected void abortInternal(Transaction txn) {
	contextMap.abort(txn);
	maybeCheckBindings(CheckBindingsType.TXN);
    }

    /* -- Implement CallbackServer -- */

    /* CallbackServer.requestDowngradeObject */

    /** {@inheritDoc} */
    @Override
    public boolean requestDowngradeObject(long oid, long conflictNodeId) {
	boolean added = dataConflicts.offerLast(
	    new DataConflict(BigInteger.valueOf(oid), conflictNodeId, false));
	assert added;
	Object lock = cache.getObjectLock(oid);
	synchronized (lock) {
	    ObjectCacheEntry entry = cache.getObjectEntry(oid);
	    if (entry == null) {
		/* Already evicted */
		return true;
	    } else if (entry.getDowngrading()) {
		/* Already being downgraded, but need to wait for completion */
		return false;
	    } else if (!entry.getWritable() && !entry.getUpgrading()) {
		/* Already downgraded and not being upgraded */
		return true;
	    } else if (!inUseForWrite(entry)) {
		/* OK to downgrade immediately */
		entry.setEvictedDowngradeImmediate(lock);
		return true;
	    } else {
		/* Downgrade when not in use */
		scheduleTask(new DowngradeObjectTask(oid));
		return false;
	    }
	}
    }

    /**
     * A {@link KernelRunnable} that downgrades an object after accessing it
     * for read.
     */
    private class DowngradeObjectTask extends FailingCompletionHandler
	implements KernelRunnable
    {
	private final long oid;
	DowngradeObjectTask(long oid) {
	    super(CachingDataStore.this);
	    this.oid = oid;
	}
	public void run() {
	    reportObjectAccess(txnProxy.getCurrentTransaction(), oid, READ);
	    Object lock = cache.getObjectLock(oid);
	    synchronized (lock) {
		ObjectCacheEntry entry = cache.getObjectEntry(oid);
		/* Check if cached for write and not downgrading */
		if (entry != null &&
		    entry.getWritable() &&
		    !entry.getDowngrading())
		{
		    entry.setEvictingDowngrade(lock);
		    updateQueue.downgradeObject(
			entry.getContextId(), oid, this);
		}
	    }
	}
	public String getBaseTaskType() {
	    return getClass().getName();
	}
	public void completed() {
	    Object lock = cache.getObjectLock(oid);
	    synchronized (lock) {
		cache.getObjectEntry(oid).setEvictedDowngrade(lock);
	    }
	}
	public String toString() {
	    return "DowngradeObjectTask[oid:" + oid + "]";
	}
    }

    /* CallbackServer.requestEvictObject */

    /** {@inheritDoc} */
    @Override
    public boolean requestEvictObject(long oid, long conflictNodeId) {
	boolean added = dataConflicts.offerLast(
	    new DataConflict(BigInteger.valueOf(oid), conflictNodeId, true));
	assert added;
	Object lock = cache.getObjectLock(oid);
	synchronized (lock) {
	    ObjectCacheEntry entry = cache.getObjectEntry(oid);
	    if (entry == null) {
		/* Already evicted */
		return true;
	    } else if (entry.getDecaching()) {
		/* Already being evicted, but need to wait for completion */
		return false;
	    } else if (!entry.getReading() &&
		       !entry.getUpgrading() &&
		       !inUse(entry))
	    {
		/*
		 * Not reading, not upgrading, and not in use, so OK to evict
		 * immediately
		 */
		entry.setEvictedImmediate(lock);
		cache.removeObjectEntry(oid);
		return true;
	    } else {
		/* Evict when not in use */
		scheduleTask(new EvictObjectTask(oid));
		return false;
	    }
	}
    }

    /**
     * A {@code CompletionHandler} that updates the cache for an object that
     * has been evicted.
     */
    private class EvictObjectCompletionHandler
	extends FailingCompletionHandler
    {
	final long oid;
	EvictObjectCompletionHandler(long oid) {
	    super(CachingDataStore.this);
	    this.oid = oid;
	    pendingEvictions.incrementAndGet();
	}
	public void completed() {
	    Object lock = cache.getObjectLock(oid);
	    synchronized (lock) {
		cache.getObjectEntry(oid).setEvicted(lock);
		cache.removeObjectEntry(oid);
		pendingEvictions.decrementAndGet();
	    }
	}
	public void failed(Throwable exception) {
	    pendingEvictions.decrementAndGet();
	    super.failed(exception);
	}
    }

    /**
     * A {@link KernelRunnable} that evicts an object after accessing it for
     * write.
     */
    private class EvictObjectTask extends EvictObjectCompletionHandler
	implements KernelRunnable
    {
	EvictObjectTask(long oid) {
	    super(oid);
	}
	public void run() {
	    reportObjectAccess(txnProxy.getCurrentTransaction(), oid, WRITE);
	    Object lock = cache.getObjectLock(oid);
	    synchronized (lock) {
		ObjectCacheEntry entry = cache.getObjectEntry(oid);
		/* Check if cached and not evicting  */
		if (entry != null &&
		    entry.getReadable() &&
		    !entry.getDecaching())
		{
		    entry.setEvicting(lock);
		    updateQueue.evictObject(entry.getContextId(), oid, this);
		} else {
		    pendingEvictions.decrementAndGet();
		}
	    }
	}
	public String getBaseTaskType() {
	    return getClass().getName();
	}
	public String toString() {
	    return "EvictObjectTask[oid:" + oid + "]";
	}
    }

    /* CallbackServer.requestDowngradeBinding */

    /** {@inheritDoc} */
    @Override
    public boolean requestDowngradeBinding(String name, long conflictNodeId) {
	boolean added = dataConflicts.offerLast(
	    new DataConflict(getNameForAccess(name), conflictNodeId, false));
	assert added;
	BindingKey nameKey = BindingKey.getAllowLast(name);
	for (int i = 0; true; i++) {
	    assert i < 1000 : "Too many retries";
	    /* Find cache entry for name or next higher name */
	    BindingCacheEntry entry = cache.getCeilingBindingEntry(nameKey);
	    if (entry == null) {
		/* No entry -- already evicted */
		return true;
	    }
	    Object lock = cache.getBindingLock(entry.key);
	    synchronized (lock) {
		if (!nameKey.equals(entry.key)) {
		    BindingCacheEntry checkEntry =
			cache.getHigherBindingEntry(nameKey);
		    if (checkEntry != entry) {
			/* Not next entry -- try again */
			continue;
		    } else if (!entry.getIsNextEntry(nameKey)) {
			/*
			 * Next entry does not cover name, so name must already
			 * be evicted
			 */
			return true;
		    }
		}
		if (entry.getPendingPrevious() || entry.getUpgrading() ||
		    inUseForWrite(entry))
		{
		    /*
		     * Downgrade when not pending previous, upgrading, or in
		     * use for write
		     */
		    scheduleTask(new DowngradeBindingTask(nameKey));
		    return false;
		} else if (entry.getDecaching() || entry.getDowngrading()) {
		    /*
		     * Already being evicted or downgraded, but need to wait
		     * for completion
		     */
		    return false;
		} else {
		    /* OK to downgrade immediately */
		    entry.setEvictedDowngradeImmediate(lock);
		    if (!nameKey.equals(entry.key)) {
			/*
			 * Name was unbound -- tell server that the next bound
			 * key has also been downgraded.  OK to use the
			 * earliest possible context ID since the entry is not
			 * in use for write
			 */
			updateQueue.downgradeBinding(
			    1, entry.key.getName(),
			    new NullCompletionHandler());
		    }
		    return true;
		}
	    }
	}
    }

    /**
     * A {@code CompletionHandler} that does nothing on successful
     * completion.
     */
    private class NullCompletionHandler extends FailingCompletionHandler {
	NullCompletionHandler() {
	    super(CachingDataStore.this);
	}
	public void completed() { }
    }

    /**
     * A {@link KernelRunnable} that downgrades a binding after accessing it
     * for read.
     */
    private class DowngradeBindingTask implements KernelRunnable {
	private final BindingKey nameKey;
	DowngradeBindingTask(BindingKey nameKey) {
	    this.nameKey = nameKey;
	}
	public void run() {
	    reportNameAccess(txnProxy.getCurrentTransaction(),
			     nameKey.getName(), READ);
	    long stop =
		addCheckOverflow(System.currentTimeMillis(), lockTimeout);
	    for (int i = 0; true; i++) {
		assert i < 1000 : "Too many retries";
		/* Find cache entry for name or next higher name */
		BindingCacheEntry entry =
		    cache.getCeilingBindingEntry(nameKey);
		if (entry == null) {
		    /* No entry -- already evicted */
		    return;
		}
		Object lock = cache.getBindingLock(entry.key);
		synchronized (lock) {
		    if (nameKey.equals(entry.key)) {
			entry.awaitNotPendingPrevious(lock, stop);
		    } else if (!assureNextEntry(entry, nameKey, lock, stop)) {
			/* Not the next entry -- try again */
			continue;
		    } else if (!entry.getIsNextEntry(nameKey)) {
			/*
			 * The next entry does not cover the name, so the name
			 * must already be evicted
			 */
			return;
		    }			
		    if (entry.getDecaching() || entry.getDowngrading()) {
			/* Already being evicted or downgraded */
			return;
		    } 
		    assert !entry.getUpgrading();
		    assert !entry.getPendingPrevious();
		    /* Downgrade */
		    entry.setEvictingDowngrade(lock);
		    updateQueue.downgradeBinding(
			entry.getContextId(), entry.key.getName(),
			new DowngradeCompletionHandler(entry.key));
		    if (!nameKey.equals(entry.key)) {
			/*
			 * Notify the server that the requested name was
			 * downgraded in addition to the name for the entry
			 * found
			 */
			updateQueue.downgradeBinding(
			    entry.getContextId(), nameKey.getName(),
			    new NullCompletionHandler());
		    }
		}
	    }
	}
	public String getBaseTaskType() {
	    return getClass().getName();
	}
	public String toString() {
	    return "DowngradeBindingTask[nameKey:" + nameKey + "]";
	}
    }

    /**
     * A {@code CompletionHandler} that updates the cache for a binding that
     * has been downgraded.
     */
    private class DowngradeCompletionHandler extends FailingCompletionHandler {
	private final BindingKey nameKey;
	DowngradeCompletionHandler(BindingKey nameKey) {
	    super(CachingDataStore.this);
	    this.nameKey = nameKey;
	}
	public void completed() {
	    Object lock = cache.getBindingLock(nameKey);
	    synchronized (lock) {
		cache.getBindingEntry(nameKey).setEvictedDowngrade(lock);
	    }
	}
    }

    /* CallbackServer.requestEvictBinding */

    /** {@inheritDoc} */
    @Override
    public boolean requestEvictBinding(String name, long conflictNodeId) {
	boolean added = dataConflicts.offerLast(
	    new DataConflict(getNameForAccess(name), conflictNodeId, true));
	assert added;
	BindingKey nameKey = BindingKey.getAllowLast(name);
	for (int i = 0; true; i++) {
	    assert i < 1000 : "Too many retries";
	    /* Find cache entry for name or next higher name */
	    BindingCacheEntry entry = cache.getCeilingBindingEntry(nameKey);
	    if (entry == null) {
		/* No entry -- already evicted */
		return true;
	    }
	    Object lock = cache.getBindingLock(entry.key);
	    synchronized (lock) {
		if (!nameKey.equals(entry.key)) {
		    /* Check for the right next entry */
		    BindingCacheEntry checkEntry =
			cache.getHigherBindingEntry(nameKey);
		    if (checkEntry != entry) {
			/* Not the next entry -- try again */
			continue;
		    } else if (!entry.getIsNextEntry(nameKey)) {
			/*
			 * The next entry does not cover the name, so the name
			 * must already be evicted
			 */
			return true;
		    }
		}
		if (entry.getPendingPrevious() || entry.getReading() ||
		    entry.getUpgrading() || entry.getDowngrading() ||
		    inUse(entry))
		{
		    /*
		     * Evict when not pending previous, reading, upgrading or
		     * downgrading
		     */
		    scheduleTask(new EvictBindingTask(nameKey));
		    return false;
		} else if (entry.getDecaching()) {
		    /*
		     * Already being evicted, but need to wait for completion
		     */
		    return false;
		} else if (nameKey.equals(entry.key)) {
		    /* Entry is not in use -- evict */
		    entry.setEvictedImmediate(lock);
		    cache.removeBindingEntry(nameKey);
		    return true;
		} else {
		    /*
		     * Entry not in use -- update previous key to not cover the
		     * evicted part
		     */
		    assert nameKey.compareTo(entry.getPreviousKey()) > 0;
		    entry.setPreviousKey(nameKey, false);
		    return true;
		}
	    }
	}
    }

    /**
     * A {@code CompletionHandler} that updates the cache for a binding that
     * has been evicted.
     */
    private class EvictBindingCompletionHandler
	extends FailingCompletionHandler
    {
	private final BindingKey nameKey;
	EvictBindingCompletionHandler(BindingKey nameKey) {
	    super(CachingDataStore.this);
	    this.nameKey = nameKey;
	    pendingEvictions.incrementAndGet();
	}
	public void completed() {
	    Object lock = cache.getBindingLock(nameKey);
	    synchronized (lock) {
		cache.getBindingEntry(nameKey).setEvicted(lock);
		cache.removeBindingEntry(nameKey);
		pendingEvictions.decrementAndGet();
	    }
	}
	public void failed(Throwable exception) {
	    pendingEvictions.decrementAndGet();
	    super.failed(exception);
	}
    }

    /**
     * A {@code CompletionHandler} that updates the cache for an unbound name
     * that has been evicted.
     */
    private class EvictUnboundNameCompletionHandler
	extends FailingCompletionHandler
    {
	final BindingKey nameKey;
	final BindingKey entryKey;
	EvictUnboundNameCompletionHandler(BindingKey nameKey,
					  BindingKey entryKey)
	{
	    super(CachingDataStore.this);
	    this.nameKey = nameKey;
	    this.entryKey = entryKey;
	}
	public void completed() {
	    Object lock = cache.getBindingLock(entryKey);
	    synchronized (lock) {
		BindingCacheEntry entry = cache.getBindingEntry(entryKey);
		assert nameKey.compareTo(entry.getPreviousKey()) > 0;
		entry.setPreviousKey(nameKey, false);
		entry.setNotPendingPrevious(lock);
	    }
	}
    }

    /**
     * A {@link KernelRunnable} that evicts a binding after accessing it for
     * write.
     */
    private class EvictBindingTask implements KernelRunnable {
	private final BindingKey nameKey;
	EvictBindingTask(BindingKey nameKey) {
	    this.nameKey = nameKey;
	}
	public void run() {
	    reportNameAccess(txnProxy.getCurrentTransaction(),
			     nameKey.getName(), WRITE);
	    long stop =
		addCheckOverflow(System.currentTimeMillis(), lockTimeout);
	    for (int i = 0; true; i++) {
		assert i < 1000 : "Too many retries";
		/* Find cache entry for name or next higher name */
		BindingCacheEntry entry = cache.getCeilingBindingEntry(nameKey);
		if (entry == null) {
		    /* No entry -- already evicted */
		    return;
		}
		Object lock = cache.getBindingLock(entry.key);
		synchronized (lock) {
		    if (nameKey.equals(entry.key)) {
			entry.awaitNotPendingPrevious(lock, stop);
			if (entry.getDecaching()) {
			    /* Already being evicted */
			    return;
			}
		    } else if (!assureNextEntry(entry, nameKey, lock, stop)) {
			/* Not the next entry -- try again */
			continue;
		    } else if (!entry.getIsNextEntry(nameKey)) {
			/*
			 * The next entry does not cover the name, so the name
			 * must already be evicted
			 */
			return;
		    }
		    assert !entry.getReading() && !entry.getUpgrading() &&
			!entry.getDowngrading()
			: "Entry should not be reading, upgrading," +
			  " or downgrading: " + entry;
		    /* Evict */
		    if (nameKey.equals(entry.key)) {
			assert !entry.getPendingPrevious();
			entry.setEvicting(lock);
			updateQueue.evictBinding(
			    entry.getContextId(), nameKey.getName(),
			    new EvictBindingCompletionHandler(nameKey));
		    } else {
			entry.setPendingPrevious();
			updateQueue.evictBinding(
			    entry.getContextId(), nameKey.getName(),
			    new EvictUnboundNameCompletionHandler(
				nameKey, entry.key));
		    }
		}
	    }
	}
	public String getBaseTaskType() {
	    return getClass().getName();
	}
	public String toString() {
	    return "EvictBindingTask[nameKey:" + nameKey + "]";
	}
    }

    /* -- Implement FailureReporter -- */

    /** {@inheritDoc} */
    @Override
    public void reportFailure(Throwable exception) {
	logger.logThrow(WARNING, exception, "CachingDataStore failed");
	synchronized (watchdogServiceSync) {
	    if (watchdogService == null) {
		if (failureBeforeWatchdog != null) {
		    failureBeforeWatchdog = exception;
		}
	    } else {
		Thread thread = new Thread(CLASSNAME + ".reportFailure") {
		    public void run() {
			watchdogService.reportFailure(
			    nodeId, CachingDataStore.class.getName());
		    }
		};
		thread.start();
	    }
	}
    }

    /* -- Methods related to shutdown -- */

    /**
     * Checks whether a shutdown has been requested.
     *
     * @return	whether a shutdown has been requested
     */
    boolean getShutdownRequested() {
	synchronized (shutdownSync) {
	    return shutdownState != ShutdownState.NOT_REQUESTED;
	}
    }

    /**
     * Checks whether a shutdown has been requested and all active transactions
     * have completed.
     */
    boolean getShutdownTxnsCompleted() {
	synchronized (shutdownSync) {
	    switch (shutdownState) {
	    case NOT_REQUESTED:
	    case REQUESTED:
		return false;
	    case TXNS_COMPLETED:
	    case COMPLETED:
		return true;
	    default:
		throw new AssertionError();
	    }
	}
    }

    /**
     * Notes that a transaction is being started.
     *
     * @throws	IllegalStateException if shutdown has been requested
     */
    void txnStarted() {
	synchronized (shutdownSync) {
	    if (getShutdownRequested()) {
		throw new IllegalStateException(
		    "Data store is shut down");
	    }
	    txnCount++;
	}
    }

    /** Notes that a transaction has finished. */
    void txnFinished() {
	synchronized (shutdownSync) {
	    txnCount--;
	    assert txnCount >= 0;
	    if (shutdownState == ShutdownState.REQUESTED && txnCount == 0) {
		shutdownSync.notifyAll();
	    }
	}
    }

    /**
     * Waits for active transactions to complete, assuming that a shutdown has
     * been requested.
     *
     * @throws	IllegalStateException if shutdown has not been requested
     */
    void awaitTxnShutdown() {
	synchronized (shutdownSync) {
	    switch (shutdownState) {
	    case NOT_REQUESTED:
		throw new IllegalStateException("Shutdown not requested");
	    case REQUESTED:
		while (txnCount > 0) {
		    try {
			shutdownSync.wait();
		    } catch (InterruptedException e) {
		    }
		}
		shutdownState = ShutdownState.TXNS_COMPLETED;
		break;
	    case TXNS_COMPLETED:
	    case COMPLETED:
		return;
	    default:
		throw new AssertionError();
	    }
	}
    }

    /* -- Other methods -- */

    /**
     * Returns the associated data store server.
     *
     * @return	the server
     */
    CachingDataStoreServer getServer() {
	return server;
    }

    /**
     * Returns the associated update queue.
     *
     * @return	the update queue
     */
    UpdateQueue getUpdateQueue() {
	return updateQueue;
    }

    /**
     * Returns the associated cache.
     *
     * @return	the cache
     */
    Cache getCache() {
	return cache;
    }

    /**
     * Schedules a kernel task with the transaction scheduler.  Tasks that
     * throw non-retryable exceptions will have those exceptions reported as
     * node failures.
     *
     * @param	task the task
     */
    void scheduleTask(final KernelRunnable task) {
	txnScheduler.scheduleTask(
	    new KernelRunnable() {
		public void run() {
		    try {
			task.run();
		    } catch (Throwable t) {
			logger.logThrow(WARNING, t, "Task {0} throws", task);
			if (t instanceof ExceptionRetryStatus &&
			    ((ExceptionRetryStatus) t).shouldRetry())
			{
			    if (t instanceof RuntimeException) {
				throw (RuntimeException) t;
			    } else if (t instanceof Error) {
				throw (Error) t;
			    }
			}
			reportFailure(t);
		    }
		}
		public String getBaseTaskType() {
		    return task.getBaseTaskType();
		}
	    },
	    taskOwner);
    }

    /**
     * Returns the associated node ID.
     *
     * @return	the node ID
     */
    long getNodeId() {
	return nodeId;
    }

    /**
     * Returns the number of milliseconds to continue retrying I/O operations
     * before determining that the failure is permanent.
     *
     * @return	the maximum retry
     */
    long getMaxRetry() {
	return maxRetry;
    }

    /**
     * Returns the number of milliseconds to wait before retrying a failed I/O
     * operation.
     *
     * @return	the retry wait
     */
    long getRetryWait() {
	return retryWait;
    }

    /**
     * Returns the number of milliseconds to wait when attempting to obtain a
     * lock.
     *
     * @return	the lock timeout
     */
    long getLockTimeout() {
	return lockTimeout;
    }

    /**
     * Checks if an entry is currently in use, either by an active or pending
     * transaction, or because it is a binding entry that has a pending
     * operation on a previous entry.  The lock associated with the entry
     * should be held.
     *
     * @param	entry the cache entry
     * @return	whether the entry is currently in use
     */
    boolean inUse(BasicCacheEntry<?, ?> entry) {
	assert Thread.holdsLock(cache.getEntryLock(entry));
	return
	    !(entry.getContextId() < updateQueue.lowestPendingContextId()) ||
	    (entry instanceof BindingCacheEntry &&
	     ((BindingCacheEntry) entry).getPendingPrevious());
    }

    /**
     * Checks if an entry is both writable and currently in use, either by an
     * active or pending transaction, or because it is a binding entry that has
     * a pending operation on a previous entry.  The lock associated with the
     * entry should be held.
     *
     * @param	entry the cache entry
     * @return	whether the entry is writable and currently in use
     */
    boolean inUseForWrite(BasicCacheEntry<?, ?> entry) {
	return entry.getWritable() && inUse(entry);
    }

    /**
     * Schedules an operation that contacts the server to fetch data or write
     * access.
     *
     * @param	runnable the operation
     */
    private void scheduleFetch(Runnable runnable) {
	fetchExecutor.execute(runnable);
    }

    /**
     * Checks the consistency of bindings in the cache, if checking bindings
     * has been requested.
     */
    private void maybeCheckBindings(CheckBindingsType callType) {
	if (checkBindings.compareTo(callType) < 0) {
	    cache.checkBindings();
	}
    }

    /* -- Utility classes -- */

    /**
     * A {@code Thread} that chooses least recently used entries to evict from
     * the cache as needed to make space for new entries.
     */
    private class EvictionThread extends Thread
	implements Cache.FullNotifier
    {

	/** Whether the cache is full. */
	private boolean cacheIsFull;

	/**
	 * Whether the evictor has reserved cache entries for use during
	 * eviction.
	 */
	private boolean reserved;

	/** An iterator over all cache entries. */
	private Iterator<BasicCacheEntry<?, ?>> entryIterator;

	/** Creates an instance of this class. */
	EvictionThread() {
	    super(CLASSNAME + ".eviction");
	}

	/* -- Implement Cache.FullNotifier -- */
	public synchronized void cacheIsFull() {
	    cacheIsFull = true;
	    notifyAll();
	}

	/* -- Implement Runnable -- */

	@Override
	public void run() {
	    logger.log(FINEST, "Start eviction thread");
	    /* Set up the initial reserve */
	    if (cache.tryReserve(evictionReserveSize)) {
		reserved = true;
	    }
	    entryIterator = cache.getEntryIterator(evictionBatchSize);
	    while (!getShutdownTxnsCompleted()) {
		if (reserved) {
		    synchronized (this) {
			if (!cacheIsFull) {
			    /* Enough space -- wait to get full */
			    logger.log(FINE, "Waiting for cache full");
			    try {
				wait();
			    } catch (InterruptedException e) {
			    }
			    continue;
			} else {
			    cacheIsFull = false;
			}
		    }
		    /*
		     * The cache is full -- release the reserve and start
		     * evicting
		     */
		    logger.log(FINE, "Cache full, starting eviction");
		    cache.release(evictionReserveSize);
		    reserved = false;
		} else if (cache.available() + pendingEvictions.get() >=
			   2 * evictionReserveSize)
		{
		    /*
		     * The cache has plenty of space -- try to set up the
		     * reserve
		     */
		    if (cache.tryReserve(evictionReserveSize)) {
			reserved = true;
		    }
		} else {
		    /*
		     * Need to initiate more evictions to be on target for
		     * obtaining two times the reserve size of free entries
		     */
		    tryEvict();
		}
	    }
	}

	/**
	 * Scan evictionBatchSize entries in the cache, and evict the best
	 * candidate.
	 */
	private void tryEvict() {
	    BasicCacheEntry<?, ?> bestEntry = null;
	    EntryInfo bestInfo = null;

	    for (int i = 0; i < evictionBatchSize; i++) {
		if (!entryIterator.hasNext()) {
		    entryIterator = cache.getEntryIterator(evictionBatchSize);
		    if (!entryIterator.hasNext()) {
			break;
		    }
		}
		BasicCacheEntry<?, ?> entry = entryIterator.next();
		if (getShutdownTxnsCompleted()) {
		    return;
		} else if (i >= evictionBatchSize) {
		    break;
		}
		synchronized (cache.getEntryLock(entry)) {
		    if (entry.getDecached() || entry.getDecaching()) {
			/* Already decached or decaching */
			continue;
		    }
		    EntryInfo entryInfo = new EntryInfo(
			inUse(entry), entry.getWritable(),
			entry.getContextId());
		    if (bestEntry == null || entryInfo.preferTo(bestInfo)) {
			bestEntry = entry;
			bestInfo = entryInfo;
		    }
		}
	    }
	    if (bestEntry != null) {
		Object lock = cache.getEntryLock(bestEntry);
		synchronized (lock) {
		    if (!bestEntry.getDecached() &&
			bestInfo.contextId == bestEntry.getContextId())
		    {
			if (!inUse(bestEntry)) {
			    logger.log(FINEST, "Evicting immediately: {0}",
				       bestEntry);
			    bestEntry.setEvicting(lock);
			    if (bestEntry instanceof ObjectCacheEntry) {
				Long key = ((ObjectCacheEntry) bestEntry).key;
				updateQueue.evictObject(
				    bestEntry.getContextId(), key,
				    new EvictObjectCompletionHandler(key));
			    } else {
				BindingKey key =
				    ((BindingCacheEntry) bestEntry).key;
				updateQueue.evictBinding(
				    bestEntry.getContextId(),
				    key.getNameAllowLast(),
				    new EvictBindingCompletionHandler(key));
			    }
			} else {
			    logger.log(FINEST, "Scheduling eviction: {0}",
				       bestEntry);
			    scheduleTask(
				(bestEntry instanceof ObjectCacheEntry)
				? new EvictObjectTask((Long) bestEntry.key)
				: new EvictBindingTask(
				    (BindingKey) bestEntry.key));
			}
		    }
		}
	    }
	}
    }

    /**
     * Records information about a cache entry for use by the evictor when
     * comparing entries for LRU eviction.
     */
    private static class EntryInfo {

	/** Whether the entry was in use. */
	private final boolean inUse;

	/** Whether the entry was in use for write. */
	private final boolean inUseForWrite;

	/** The transaction context ID when the entry was last used. */
	final long contextId;

	/** Creates an instance of this class. */
	EntryInfo(boolean inUse, boolean inUseForWrite, long contextId) {
	    this.inUse = inUse;
	    this.inUseForWrite = inUseForWrite;
	    this.contextId = contextId;
	}

	/**
	 * Determines whether the entry associated with this instance should be
	 * preferred to the one associated with the argument.  Entries are
	 * preferred if they are not in use, if they are not in use for read,
	 * and if they were last used by an older transaction.
	 */
	boolean preferTo(EntryInfo other) {
	    if (inUse != other.inUse) {
		return !inUse;
	    } else if (inUseForWrite != other.inUseForWrite) {
		return !inUseForWrite;
	    } else {
		return contextId < other.contextId;
	    }
	}
    }

    /**
     * A {@code Thread} that delivers information about data access conflicts
     * to data conflict listeners.
     */
    private class DataConflictThread extends Thread {

	/** Creates an instance of this class. */
	DataConflictThread() {
	    super(CLASSNAME + ".dataConflict");
	}

	/* -- Implement Runnable -- */

	@Override
	public void run() {
	    logger.log(FINEST, "Start access conflict thread");
	    while (!getShutdownTxnsCompleted()) {
		DataConflict conflict;
		try {
		    conflict = dataConflicts.takeFirst();
		} catch (InterruptedException e) {
		    continue;
		}
		/*
		 * Listeners are only added, not removed, so it's OK to not
		 * use a single synchronized for the calls to size and get.
		 */
		for (int i = 0; i < dataConflictListeners.size(); i++) {
		    conflict.notify(dataConflictListeners.get(i));
		}
	    }
	}
    }

    /**
     * Manages information about a conflicting data access from another node.
     */
    private static class DataConflict {

	/** The identifier for the object accessed. */
	private final Object accessId;

	/** The node ID for the remote node performing the access. */
	private final long nodeId;

	/** Whether the access was for update. */
	private final boolean forUpdate;

	/**
	 * Creates an instance of this class.
	 *
	 * @param	accessId the identifier for the object accessed
	 * @param	nodeId the node ID for the remote node performing the
	 *		access
	 * @param	forUpdate whether the access was for update
	 */
	DataConflict(Object accessId, long nodeId, boolean forUpdate) {
	    this.accessId = accessId;
	    this.nodeId = nodeId;
	    this.forUpdate = forUpdate;
	}

	/**
	 * Notifies the listener of the access represented by this object.
	 *
	 * @param	listener the listener
	 */
	void notify(DataConflictListener listener) {
	    if (logger.isLoggable(FINEST)) {
		logger.log(FINEST,
			   "notify listener:" + listener +
			   ", accessId:" + accessId +
			   ", nodeId:" + nodeId +
			   ", forUpdate:" + forUpdate);
	    }
	    try {
		listener.nodeConflictDetected(accessId, nodeId, forUpdate);
		if (logger.isLoggable(FINEST)) {
		    logger.log(FINEST,
			       "notify listener:" + listener +
			       ", accessId:" + accessId +
			       ", nodeId:" + nodeId +
			       ", forUpdate:" + forUpdate +
			       " returns");
		}
	    } catch (Throwable t) {
		if (logger.isLoggable(FINEST)) {
		    logger.logThrow(FINEST, t,
				    "notify listener:" + listener +
				    ", accessId:" + accessId +
				    ", nodeId:" + nodeId +
				    ", forUpdate:" + forUpdate +
				    " throws");
		}
	    } 
	}
    }
}