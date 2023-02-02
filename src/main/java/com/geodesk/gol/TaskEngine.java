/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol;


import com.clarisma.common.util.Log;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

public abstract class TaskEngine<T>
{
    private long startTime;
    private final CountDownLatch[] phases;
    private Throwable error;
    private int threadCount = Runtime.getRuntime().availableProcessors(); // TODO
    private int queueSize = threadCount * 2; // TODO
    private Thread[] workerThreads;
    private OutputThread outputThread;
    private final BlockingQueue<T> inputQueue;
    private BlockingQueue<Runnable> outputQueue;
    private final T endMarker;

    protected TaskEngine(T endMarker, int groups, boolean useOutputThread)
    {
        this.endMarker = endMarker;
        inputQueue = new LinkedBlockingQueue<>(queueSize);
        outputQueue = new LinkedBlockingQueue<>(queueSize);
        startTime = System.currentTimeMillis();
        workerThreads = new Thread[threadCount];
        phases = new CountDownLatch[groups * 2];
        if(useOutputThread)
        {
            outputThread = new OutputThread();
            outputThread.setName("output");
        }
        for(int i=0; i<phases.length; i++)
        {
            phases[i] = new CountDownLatch(threadCount);
        }
    }


    /**
     * Called to create a new worker thread. Subclasses should override this
     * method and create a custom WorkerThread.
     *
     * @return a new WorkerThread instance
     */
    protected abstract WorkerThread createWorker() throws Exception;

    /**
     * Aborts all processing. Interrupts all threads and shuts down.
     *
     * @param ex a {@link Throwable} indicating the cause of the failure
     */
    protected void fail(Throwable ex)
    {
        error = ex;
        if(outputThread != null) outputThread.interrupt();
        for(int i=0; i<workerThreads.length; i++)
        {
            workerThreads[i].interrupt();
        }
        inputQueue.clear();
    }


    /**
     * Thread that processes sequential instructions submitted via
     * {@link #output(Runnable)}.
     */
    private class OutputThread extends Thread
    {
        @Override public void run()
        {
            try
            {
                for (;;)
                {
                    if(interrupted()) break;
                    Runnable task = outputQueue.take();
                    task.run();
                }
            }
            catch(InterruptedException ex)
            {
                // do nothing, we're done
            }
            catch(Throwable ex)
            {
                fail(ex);
            }
        }
    }

    /**
     * Executes the given {@link Runnable} on the output thread, ensuring
     * that such operations happen synchronously. This method can be safely
     * called by the worker threads. It is more efficient than wrapping
     * instructions in a {@code synchronized} block, as it uses a blocking
     * queue to hand off tasks to the output thread.
     *
     * @param task a {@code Runnable} that performs synchronous instructions
     * @throws InterruptedException if interrupted while waiting for space
     *     in the output queue
     *
     */
    protected void output(Runnable task) throws InterruptedException
    {
        outputQueue.put(task);
    }

    protected abstract class WorkerThread extends Thread
    {
        private int currentPhase;

        private void debug(String msg, Object... args)
        {
            Log.debug(Thread.currentThread().getName() + ": " + msg, args);
        }

        protected int currentPhase()
        {
            return currentPhase;
        }

        /**
         * Moves the current thread into the specified phase. The thread waits until all other threads have completed
         * the preceding phases.
         *
         * @param newPhase
         * @throws InterruptedException
         */
        private void switchPhase(int newPhase) throws InterruptedException
        {
            //debug("Switching to phase %d...", newPhase);
            if (newPhase == currentPhase) return;
            if (newPhase < currentPhase)
            {
                throw new RuntimeException(
                    "Attempt to switch to previous phase (%d)" +
                        "from current phase (%d)".formatted(newPhase, currentPhase));
            }
            for (int i = currentPhase; i < newPhase; i++)
            {
                CountDownLatch phase = phases[i];
                if (outputThread != null)
                {
                    //debug("Placed countDown instruction into output thread.");
                    outputQueue.put(() -> phase.countDown());
                }
                else
                {
                    phase.countDown();
                }
            }
            phases[newPhase - 1].await();
            currentPhase = newPhase;
            //debug("Proceeding with phase %d", currentPhase);
            //debug("All threads completed phase %d...", newPhase-1);
        }

        /**
         * Marks all phases as complete, without invoking any processing (and without waiting for the other threads to
         * complete their phases). Used to cleanly shut down in the event of an unrecoverable error.
         */
        private void cancelPhases()
        {
            while (currentPhase < phases.length)
            {
                phases[currentPhase++].countDown();
            }
        }

        @Override public void run()
        {
            for (;;)
            {
                try
                {
                    T task = inputQueue.take();
                    if(task == endMarker)
                    {
                        switchPhase(currentPhase+1);
                        postProcess();
                        switchPhase(currentPhase+1);
                        if(currentPhase == phases.length)
                        {
                            if(outputThread != null) outputThread.interrupt();
                            break;
                        }
                    }
                    else
                    {
                        process(task);
                    }
                }
                catch (InterruptedException ex)
                {
                    cancelPhases();
                    break;  // stop processing
                }
                catch (Throwable ex)
                {
                    cancelPhases();
                    fail(ex);   // everything else is a real error
                }
            }
        }

        protected abstract void process(T task) throws Exception;

        protected void postProcess() throws Exception
        {
            // do nothing
        }
    }

    protected void checkError()
    {
        if(error != null) throw new RuntimeException(error);
    }

    public void start()
    {
        startTime = System.currentTimeMillis();

        // We create the workers here instead of in the constructor since
        // they may depend on initialization fo the subclass
        for(int i=0; i<threadCount; i++)
        {
            try
            {
                WorkerThread thread = createWorker();
                thread.setName("worker-" + i);
                workerThreads[i] = thread;
            }
            catch(Exception ex)
            {
                throw new RuntimeException(
                    "Failed to create worker thread: " + ex.getMessage(), ex);
            }
        }
        if (outputThread != null) outputThread.start();
        for (int i = 0; i < workerThreads.length; i++) workerThreads[i].start();
    }

    protected void submit(T task)
    {
        checkError();
        try
        {
            inputQueue.put(task);
        }
        catch(InterruptedException ex)
        {
            // TODO
        }
    }

    public void awaitCompletionOfGroup(int n) throws InterruptedException
    {
        for(int i=0; i<threadCount; i++)
        {
            inputQueue.put(endMarker);
        }
        phases[n * 2 + 1].await();
        checkError();
    }

    /*
    public void awaitCompletion() throws InterruptedException
    {
        phases[phases.length-1].await();
        if(outputThread==null) outputThread.interrupt();
    }
     */
}

