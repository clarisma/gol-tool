package com.geodesk.gol;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

// TODO: move
// TODO: implement phases, make usable for OsmPbfReader & Validator
// TODO: Idea: turn reporting inside out, default implementation asks
//  for total. After each task, completedTask(T) is called (synchronized);
//   this in turn calls work(T) to determine how much work was completed for
//   this task
public abstract class Processor<T> implements Runnable
{
    private static final Logger log = LogManager.getLogger();

    private int threadCount = Runtime.getRuntime().availableProcessors();
    private int queueSize = threadCount * 2;
    private Thread[] workerThreads;
    private Thread outputThread;
    private BlockingQueue<Object> inputQueue;
    private long startTime;
    private CountDownLatch latch;
    private volatile Throwable error;
    private long totalWork;
    private long workCompleted;
    private String verb;
    private int percentageReported;

    protected Worker createWorker()
    {
        return new Worker();
    }

    protected void fail(Throwable ex)
    {
        if(error != null) return;       // TODO: (benign) race condition
        System.out.println(ex.getMessage());
        ex.printStackTrace();
        error = ex;
        if(outputThread != null) outputThread.interrupt();
        for(int i=0; i<workerThreads.length; i++)
        {
            workerThreads[i].interrupt();
        }
        inputQueue.clear();
    }

    protected boolean failed()
    {
        return error != null;
    }

    private static final Object END = new Object();

    protected class Worker extends Thread
    {
        protected void process(T task)
        {
            // TODO: call preTask(Worker, T) in order to handle phase switching?
            ((Runnable)task).run();
            // TODO: call completedTask(T)
        }

        @Override public void run()
        {
            for(;;)
            {
                if(isInterrupted())
                {
                    log.debug("Interrupted, ending...");
                    break;
                }
                try
                {
                    Object task = inputQueue.take();
                    if(task == END)
                    {
                        break;
                    }
                    process((T)task);
                }
                catch(InterruptedException ex)
                {
                    log.debug("Interrupted while waiting for tasks, ending...");
                    break;
                }
                catch (Throwable ex)
                {
                    fail(ex);
                    break;
                }
            }
            latch.countDown();
            log.debug("Worker thread is done.");
        }
    }

    protected abstract void feed() throws Exception;

    protected void submit(T task)
    {
        try
        {
            inputQueue.put(task);
        }
        catch(InterruptedException ex)
        {
            // TODO
        }
    }

    @Override public void run()
    {
        startTime = System.currentTimeMillis();

        inputQueue = new LinkedBlockingQueue<>(queueSize);

        workerThreads = new Thread[threadCount];
        for(int i=0; i<threadCount; i++)
        {
            Worker thread = createWorker();
            thread.setName("worker-" + i);
            workerThreads[i] = thread;
        }
        for(int i=0; i<workerThreads.length; i++) workerThreads[i].start();
        latch = new CountDownLatch(threadCount);

        try
        {
            feed();
            if(failed())
            {
                log.debug("Failed, run() quits.");
                throw error;
            }
            for (int i = 0; i < workerThreads.length; i++)
            {
                try
                {
                    inputQueue.put(END);
                }
                catch (InterruptedException ex)
                {
                    // TODO
                }
            }
            try
            {
                latch.await();
            }
            catch (InterruptedException ex)
            {
                // TODO
            }
        }
        catch(Throwable ex)
        {
            fail(ex);
        }

        // TODO: let subclass customize exception class
        if(error != null) throw new RuntimeException(error);
    }

    protected long timeElapsed()
    {
        return System.currentTimeMillis() - startTime;
    }

    protected void setTotalWork(String verb, long totalWork)
    {
        this.verb = verb;
        this.totalWork = totalWork;
    }

    protected synchronized void completed(long work)
    {
        // TODO: use more fine-grained lock; no reason to sync on entire object

        workCompleted += work;
        assert workCompleted <= totalWork;

        int percentageCompleted = (int)(workCompleted * 100 / totalWork);
        // log.debug("Completed: {} %", percentageCompleted);
        if (percentageCompleted != percentageReported)
        {
            // TODO: use System.err, respect verbosity level
            System.out.format("%s... %d%%\r", verb, percentageCompleted);
            percentageReported = percentageCompleted;
        }
    }
}
