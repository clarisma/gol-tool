package com.geodesk.gol;

import com.clarisma.common.util.Log;
import org.junit.Test;

import static org.junit.Assert.*;

public class TaskEngineTest
{
    static class Task
    {
        int number;

        public Task(int number)
        {
            this.number = number;
        }
    }

    static class TestTaskEngine extends TaskEngine<Task>
    {
        protected TestTaskEngine()
        {
            super(new Task(-1), 2, false);
        }

        class TestWorker extends WorkerThread
        {
            @Override protected void process(Task task) throws Throwable
            {
                if(task.number == 5) throw new RuntimeException("Testing: Some error occurred");
                Log.debug("[%s] Processed task %d", Thread.currentThread().getName(), task.number);
            }
        }

        @Override protected TaskEngine<Task>.WorkerThread createWorker()
        {
            return new TestWorker();
        }
    }

    @Test public void testTaskEngine() throws Exception
    {
        TestTaskEngine te = new TestTaskEngine();
        te.start();
        for(int i=0; i<6; i++) te.submit(new Task(i));
        te.awaitCompletionOfGroup(0);
        Log.debug("Group 0 completed.");
        for(int i=100; i<120; i++) te.submit(new Task(i));
        te.awaitCompletionOfGroup(1);
        Log.debug("Group 1 completed.");
    }
}