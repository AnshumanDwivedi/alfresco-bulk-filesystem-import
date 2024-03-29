/*
 * Copyright (C) 2005-2010 Alfresco Software Limited.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.

 * As a special exception to the terms and conditions of version 2.0 of 
 * the GPL, you may redistribute this Program in connection with Free/Libre 
 * and Open Source Software ("FLOSS") applications as described in Alfresco's 
 * FLOSS exception.  You should have received a copy of the text describing 
 * the FLOSS exception, and it is also available here: 
 * http://www.alfresco.com/legal/licensing"
 */

package org.alfresco.extension.bulkfilesystemimport.impl;

import java.io.File;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.alfresco.extension.bulkfilesystemimport.BulkImportStatus;
import org.alfresco.repo.content.ContentStore;
import org.alfresco.repo.policy.BehaviourFilter;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.authentication.AuthenticationUtil.RunAsWork;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.util.Pair;


/**
 * Bulk Filesystem Importer that loads the source on multiple background threads (ie. the caller
 * immediately returns, and can then poll the status via the getImportStatus method).
 *
 * @author Peter Monks (peter.monks@alfresco.com)
 */
public abstract class MultiThreadedBulkFilesystemImporter   // Note: class is abstract because it uses Spring's "lookup-method" mechanism
    extends AbstractBulkFilesystemImporter
{
    private final static Log log = LogFactory.getLog(MultiThreadedBulkFilesystemImporter.class);
    
    // Interview
    private final static long DEFAULT_COMPLETION_CHECK_INTERVAL_MS = 250;
    
    private final long       completionCheckIntervalMs;
    private final AtomicLong numberOfActiveUnitsOfWork;
    
    private String             sourceRoot;
    private ThreadPoolExecutor threadPool;
    
    
    public MultiThreadedBulkFilesystemImporter(final ServiceRegistry      serviceRegistry,
                                               final BehaviourFilter      behaviourFilter,
                                               final ContentStore         configuredContentStore,
                                               final BulkImportStatusImpl importStatus)
    {
        this(serviceRegistry, behaviourFilter, configuredContentStore, importStatus, DEFAULT_COMPLETION_CHECK_INTERVAL_MS);
    }


    public MultiThreadedBulkFilesystemImporter(final ServiceRegistry      serviceRegistry,
                                               final BehaviourFilter      behaviourFilter,
                                               final ContentStore         configuredContentStore,
                                               final BulkImportStatusImpl importStatus,
                                               final long                 completionCheckIntervalMs)
    {
        super(serviceRegistry, behaviourFilter, configuredContentStore, importStatus);
        
        this.completionCheckIntervalMs = completionCheckIntervalMs;
        this.numberOfActiveUnitsOfWork = new AtomicLong();
    }
    
    
    /**
     * Spring "lookup method" that will return a new ThreadPoolExecutor each time it's called.
     * We have to go to these extremes because:
     * 1. In some cases we need a way to forcibly stop an entire import (including all of the worker threads)
     * 2. Java's ExecutorService framework only offers one way to do this: shutting down the entire ExecutorService
     * 3. Once shutdown, a Java ExecutorService can't be restarted
     * 
     * Ergo this stuff...  *sigh*
     * 
     * @return A new ThreadPoolExecutor instance <i>(will not be null, assuming Spring is configured correctly)</i>.
     */
    protected abstract ThreadPoolExecutor createThreadPool();
    

    /**
     * @see org.alfresco.extension.bulkfilesystemimport.impl.AbstractBulkFilesystemImporter#bulkImportImpl(org.alfresco.service.cmr.repository.NodeRef, java.io.File, boolean, boolean)
     */
    @Override
    protected void bulkImportImpl(final NodeRef target,
                                  final File    source,
                                  final boolean replaceExisting,
                                  final boolean inPlaceImport)
        throws Throwable
    {
        sourceRoot = getFileName(source);
        threadPool = createThreadPool();    // Get a new ThreadPool from a Spring prototype bean
        numberOfActiveUnitsOfWork.set(0);
        
        log.info("Bulk import started from '" + sourceRoot + "'...");

        importStatus.startImport(getFileName(source),
                                 getRepositoryPath(target),
                                 inPlaceImport ? BulkImportStatus.ImportType.IN_PLACE : BulkImportStatus.ImportType.STREAMING,
                                 getBatchWeight(),
                                 threadPool);
        threadPool.submit(new UnitOfWork(target, getFileName(source), source, replaceExisting, inPlaceImport, AuthenticationUtil.getFullyAuthenticatedUser()));
        
        startCompletionMonitoringThread();
    }


    /**
     * This method starts another background thread that monitors whether the import has finished or not.
     * This is a cheap and cheerful way of working around the problem that we don't actually know when the
     * import has completed until it's no longer importing anything (i.e. there are no active tasks and the
     * task queue is empty).
     * 
     * Note that we rely on our own count of the number of active units of work (threads), because Java's
     * ExecutorService doesn't accurately keep a count (note the use of the word "approximate" in this JavaDoc:
     * http://download.oracle.com/javase/6/docs/api/java/util/concurrent/ThreadPoolExecutor.html#getActiveCount())
     */
    private void startCompletionMonitoringThread()
    {
        Runnable importCompletionLogic  = null;
        Thread   importCompletionThread = null;
        
        importCompletionLogic = new Runnable()
        {
            @Override
            public void run()
            {
                if (log.isDebugEnabled()) log.debug(Thread.currentThread().getName() + " started.");
                
                try
                {
                    // Dodgy hack to avoid race condition before the import has started
                    //###TODO: revisit this via thread notification or wotnot
                    Thread.sleep(completionCheckIntervalMs);
                    
                    while (!Thread.interrupted() && importStatus.inProgress())
                    {
                        if (numberOfActiveUnitsOfWork.get() == 0 && threadPool.getQueue().isEmpty())
                        {
                            importStatus.stopImport();
                            
                            if (log.isInfoEnabled()) log.info("Bulk import from '" + sourceRoot + "' succeeded.");
                            logStatus(importStatus);
                            
                            break; // Drop out of the while loop, thereby terminating the thread
                        }
                        else
                        {
                            Thread.sleep(completionCheckIntervalMs);
                        }
                    }
                }
                catch (final InterruptedException ie)
                {
                    if (log.isDebugEnabled()) log.debug(Thread.currentThread().getName() + " was interrupted.", ie);
                }
                finally
                {
                    // Kill the thread pool before terminating
                    if (log.isDebugEnabled()) log.debug("Shutting down worker thread pool.");
                    threadPool.shutdown();
                }
            }
        };
        
        importCompletionThread = new Thread(importCompletionLogic, "BulkFilesystemImport-CompletionMonitorThread");
        importCompletionThread.setDaemon(true);
        importCompletionThread.start();
    }
    
    
    /**
     * This immutable class encapsulates a single UnitOfWork in the multi-threaded bulk importer.  Each unit-of-work is
     * processed serially (i.e. is not internally multi-threaded), but may be batched into multiple transactions. 
     */
    private final class UnitOfWork
        implements Runnable
    {
        private final NodeRef target;
        private final String  sourceRoot;
        private final File    source;
        private final boolean replaceExisting;
        private final boolean inPlaceImport;
        private final String  currentUser;
        
        private UnitOfWork(final NodeRef target,
                           final String  sourceRoot,
                           final File    source,
                           final boolean replaceExisting,
                           final boolean inPlaceImport,
                           final String  currentUser)
        {
            this.target          = target;
            this.sourceRoot      = sourceRoot;
            this.source          = source;
            this.replaceExisting = replaceExisting;
            this.inPlaceImport   = inPlaceImport;
            this.currentUser     = currentUser;
        }

        /**
         * Executes the unit of work.
         */
        @Override
        public void run()
        {
            try
            {
                numberOfActiveUnitsOfWork.incrementAndGet();
                
                AuthenticationUtil.runAs(new RunAsWork<Object>()
                {
                    @Override
                    public Object doWork()
                        throws Exception
                    {
                        List<Pair<NodeRef, File>> subDirectories = importDirectory(target, sourceRoot, source, replaceExisting, inPlaceImport);

                        // Submit each sub-directory to the thread pool for independent importation
                        for (final Pair<NodeRef, File> subDirectory : subDirectories)
                        {
                            if (subDirectory != null)
                            {
                                threadPool.submit(new UnitOfWork(subDirectory.getFirst(), sourceRoot, subDirectory.getSecond(), replaceExisting, inPlaceImport, currentUser));
                            }
                        }
                        
                        return(null);
                    }
                }, currentUser);
            }
            catch (final Throwable t)
            {
                // Log the exception and kill the entire import
                log.error("Bulk import from '" + getFileName(source) + "' failed.", t);
                
                if (log.isDebugEnabled()) log.debug("Shutting down worker thread pool.");
                threadPool.shutdown();
                importStatus.stopImport(t);
            }
            finally
            {
                numberOfActiveUnitsOfWork.decrementAndGet();
            }
        }
    }

}
