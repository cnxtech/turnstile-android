/*
 * The MIT License (MIT)
 * <p/>
 * Copyright (c) 2016 Vimeo
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.vimeo.turnstile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;

import com.vimeo.turnstile.BaseTask.TaskStateListener;
import com.vimeo.turnstile.async.NamedThreadFactory;
import com.vimeo.turnstile.database.TaskCallback;
import com.vimeo.turnstile.connectivity.NetworkEventProvider;
import com.vimeo.turnstile.connectivity.NetworkUtil;
import com.vimeo.turnstile.connectivity.NetworkUtilExtended;
import com.vimeo.turnstile.database.TaskCache;
import com.vimeo.turnstile.database.TaskDatabase;
import com.vimeo.turnstile.models.TaskError;
import com.vimeo.turnstile.preferences.BootPreferences;
import com.vimeo.turnstile.preferences.TaskPreferences;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * This is the base class responsible for managing the queue of tasks.
 * It holds the logic for adding, retrying, and cancelling tasks.
 * It can optionally interact with an {@link NetworkUtil} for network
 * based tasks as well as a {@link BaseTaskService} for tasks that
 * should continue after the app is closed.
 * <p/>
 * To get your first TaskManager set up:
 * <ol>
 * <li>Create a subclass of {@link BaseTaskManager} which has an initialize method.</li>
 * <li>Create a subclass of {@link BaseTask}.</li>
 * <li>Create a subclass of {@link BaseTaskService} and add that service to your AndroidManifest.</li>
 * </ol>
 * <p/>
 * Created by kylevenn on 2/9/16.
 */
@SuppressWarnings("unused")
public abstract class BaseTaskManager<T extends BaseTask> implements NetworkEventProvider.Listener {

    private static final String LOG_TAG = "BaseTaskManager";
    private static final int MAX_ACTIVE_TASKS = 3; // TODO: Should we up the maximum? 2/25/16 [KV]

    // ---- Executor Service ----
    private final ExecutorService mCachedExecutorService;
    // Could be Future<Object> if there is value in a return object
    private final static ConcurrentHashMap<String, Future> sTaskPool = new ConcurrentHashMap<>();

    // ---- TaskCache ----
    @NonNull
    protected final TaskCache<T> mTaskCache;

    // ---- Context ----
    @NonNull
    protected final Context mContext;

    // ---- Manager State ----
    private boolean mIsPaused;
    // If the task pool is in the process of resuming (we don't want to resume twice)
    private volatile boolean isResuming;

    @NonNull
    protected final TaskPreferences mTaskPreferences;

    // ---- Optional Builder Fields ----
    // <editor-fold desc="Builder Fields">
    private final NetworkUtil mNetworkUtil;
    @Nullable
    private final LoggingInterface<T> mLoggingInterface;
    @Nullable
    // This could also do it by broadcast and have the app's receiver decide where to go 2/9/16 [KV]
    private final Intent mNotificationIntent;

    @Nullable
    public Intent getNotificationIntent() {
        return mNotificationIntent;
    }
    // </editor-fold>

    /*
     * -----------------------------------------------------------------------------------------------------
     * Initialization
     * -----------------------------------------------------------------------------------------------------
     */
    // <editor-fold desc="Initialization">
    protected BaseTaskManager(@NonNull TaskManagerBuilder<T> taskManagerBuilder) {
        String taskName = getManagerName();
        Class<T> taskClass = getTaskClass();
        // Always use the application process - this context is a singleton itself which is the global
        // context of the process. Since the Service and App are in the same process, this shouldn't
        // be an issue.
        mContext = taskManagerBuilder.mContext.getApplicationContext();
        // TODO: Make it so network util is optional so that we might have no reliance on network 3/2/16 [KV]
        mNetworkUtil = taskManagerBuilder.mNetworkUtil;
        mLoggingInterface = taskManagerBuilder.mLoggingInterface;
        mNotificationIntent = taskManagerBuilder.mNotificationIntent;

        // Needs to be initialized with the manager name so that this instance is manager-specific
        mTaskPreferences = new TaskPreferences(mContext, taskName);
        if (mNetworkUtil instanceof NetworkUtilExtended) {
            // If we're using the default network util, provide it with the manager-specific preferences
            ((NetworkUtilExtended) mNetworkUtil).setTaskPreferences(mTaskPreferences);
        }
        mNetworkUtil.setListener(this);
        mIsPaused = mTaskPreferences.isPaused();

        // ---- Executor Service ----
        // Fixed pool holds exactly n threads. It will enqueue the remaining jobs handed to it.
        ThreadFactory namedThreadFactory = new NamedThreadFactory(taskName);
        mCachedExecutorService = Executors.newFixedThreadPool(MAX_ACTIVE_TASKS, namedThreadFactory);

        // ---- Persistence ----
        TaskDatabase<T> database = new TaskDatabase<>(mContext, taskName, taskClass);
        // Synchronous load from SQLite. Not very performant but required for simplified in-memory cache
        mTaskCache = new TaskCache<>(database);

        // ---- Boot Handling ----
        if (startOnDeviceBoot()) {
            BootPreferences.addServiceClass(mContext, getServiceClass());
        }

        resumeAllIfNecessary();
    }
    // </editor-fold>

    /*
     * -----------------------------------------------------------------------------------------------------
     * Abstract Methods
     * -----------------------------------------------------------------------------------------------------
     */
    // <editor-fold desc="Abstract Methods">

    /**
     * The subclass of {@link BaseTaskService}.
     *
     * @return the Service class driving the task manager.
     */
    // TODO: Make this nullable and make it so a service isn't required for the task execution 3/2/16 [KV]
    protected abstract Class<? extends BaseTaskService> getServiceClass();

    /**
     * The unique name used to identify this particular subclass of {@link BaseTaskManager}.
     * It needs to be unique to allow for using multiple different TaskManagers in the same application.
     */
    @NonNull
    protected abstract String getManagerName();

    /**
     * The type of tasks that this manager processes.
     *
     * @return the task type, extending {@link BaseTask}.
     */
    @NonNull
    protected abstract Class<T> getTaskClass();

    /**
     * Return if you'd like the subclass of manager to try
     * and resume its tasks when the devices first starts.
     */
    protected boolean startOnDeviceBoot() {
        return false;
    }
    // </editor-fold>

    /*
     * -----------------------------------------------------------------------------------------------------
     * TaskStateListener Implementation
     * -----------------------------------------------------------------------------------------------------
     */
    // <editor-fold desc="TaskStateListener Implementation">
    private final TaskStateListener<T> mTaskListener = new TaskStateListener<T>(getTaskClass()) {
        @Override
        public void onTaskStateChange(@NonNull T task) {
            mTaskCache.upsert(task);
            // After a retry, lets make sure the service is running
            serviceCleanup(false);
        }

        @Override
        public void onTaskCompleted(@NonNull T task) {
            logSuccess(task);
            mTaskCache.upsert(task);

            // Just remove from the task pool. We're currently executing in that thread.
            sTaskPool.remove(task.getId());
            broadcastEvent(task.getId(), TaskConstants.EVENT_SUCCESS);
            serviceCleanup(true);
        }

        @Override
        public void onTaskProgress(@NonNull T task, int progress) {
            broadcastProgress(task.getId(), progress);
        }

        @Override
        public void onTaskFailure(@NonNull T task, TaskError taskError) {
            logFailure(task, taskError);
            mTaskCache.upsert(task);

            // Just remove from the task pool. We're currently executing in that thread.
            sTaskPool.remove(task.getId());
            broadcastFailure(task.getId(), task.getTaskError());
            serviceCleanup(false);
        }
    };
    // </editor-fold>

    /*
     * -----------------------------------------------------------------------------------------------------
     * Task Accessors
     * -----------------------------------------------------------------------------------------------------
     */
    // <editor-fold desc="Task Accessors">
    @Nullable
    public T getTask(String taskId) {
        return mTaskCache.get(taskId);
    }

    @NonNull
    public Map<String, T> getTasks() {
        return mTaskCache.getTasks();
    }


    public List<T> getTasksToRun() {
        return mTaskCache.getTasksToRun();
    }

    public List<T> getDateOrderedTaskList() {
        return mTaskCache.getDateOrderedTaskList();
    }

    /**
     * @return if there are any tasks that still need to be run
     * by this task manager (that aren't currently running)
     */
    public boolean tasksRemaining() {
        // If there are tasks in the cache that `shouldRun()`
        return !mTaskCache.getTasksToRun().isEmpty();
    }

    /**
     * Determine if a task is actively running.
     *
     * @param taskId the id of the task to check
     * @return true if the provided task is executing,
     * false otherwise.
     */
    public boolean isExecuting(@NonNull String taskId) {
        T task = mTaskCache.get(taskId);
        return task != null && task.isRunning();
    }

    /**
     * Determine if a task is in the task pool.
     *
     * @param taskId the id of the task to check
     * @return true if the provided task is in
     * the task pool, false otherwise.
     */
    public static boolean isInTaskPool(@NonNull String taskId) {
        return sTaskPool.containsKey(taskId);
    }

    /**
     * Determine if a task is queued to run and not yet executing
     *
     * @param taskId checks to see if the task
     *               with this id is queued.
     * @return true if the task is queued, false otherwise.
     */
    public boolean isQueued(@NonNull String taskId) {
        if (sTaskPool.size() > MAX_ACTIVE_TASKS && isInTaskPool(taskId)) {
            T task = mTaskCache.get(taskId);
            Future future = sTaskPool.get(taskId);
            if (task != null && future != null) {
                return !isExecuting(taskId) && !future.isDone() && !future.isCancelled() && task.isReady();
            }
        }
        return false;
    }

    // </editor-fold>


    // ---------------------------------------------------------------------------------------------------
    // Task Operations
    // ---------------------------------------------------------------------------------------------------
    // <editor-fold desc="Task Operations">

    /**
     * Adds the provided {@link BaseTask} to the task queue and
     * starts execution if possible. If knowledge of successful
     * or failure for persistence of the task is required,
     * use {@link #addTask(BaseTask, TaskCallback)}
     *
     * @param task the task to add to the manager
     */
    public void addTask(@NonNull T task) {
        addTask(task, null);
    }

    /**
     * Adds the provided {@link BaseTask} to the task queue and
     * starts execution if possible. Errors are propagated back
     * to the {@link TaskCallback} passed in.
     *
     * @param task     the task to add to the manager
     * @param callback the callback to receive notification
     *                 of success and error.
     */
    public void addTask(@NonNull T task, @Nullable TaskCallback callback) {
        broadcastEvent(task.getId(), TaskConstants.EVENT_ADDED);
        // Kick off the upload stream
        addTask(task, false);
        mTaskCache.insert(task, callback);
    }

    // Eventually with failure states we can call this with isResume = false to start over
    private void addTask(@NonNull T task, boolean isResume) {
        if (task.getId() == null) {
            TaskLogger.e("Task with a null ID passed to addTask. Will not add it.");
            return;
        }

        // We set the context on the task
        task.setContext(mContext);
        task.setStateListener(mTaskListener);
        task.setNetworkUtil(mNetworkUtil);

        // Only kick off the task if there is internet (and it's not paused)
        // If no network, it's persisted elsewhere so this won't effect it starting later
        // We also don't want to re-add a task if it's already in the queue (since overwriting the value
        // in the hashmap won't cancel the task that's running. This way we should never be able to have
        // two of the same task running at once)
        if ((!mIsPaused && hasNetwork()) && !sTaskPool.containsKey(task.getId())) {
            task.setIsRetry(isResume);
            Future taskFuture = mCachedExecutorService.submit(task);
            sTaskPool.put(task.getId(), taskFuture);
            // Let's ensure the service is running - it's okay to call this method excessively 2/29/16 [KV]
            startService();
        } else {
            // The manager is suspended for one of the above cases in the `if`. Broadcast out the fact that
            // we can't actually add this task 3/1/16 [KV]
            broadcastIsManagerSuspended();
        }
    }

    /**
     * Cancel the thread for that video and remove from local db
     * NOTE: this doesn't delete the video from the server.
     */
    public void cancelTask(String id) {
        if (mLoggingInterface != null) {
            T task = mTaskCache.get(id);
            mLoggingInterface.logTaskCancel(task);
        }
        removeFromTaskPool(id);
        // TODO: deleteIfInDb();
        // returns true if it was actually in the db
        mTaskCache.remove(id);
        // Since we just cancelled a thread, let's check to see if it still has any left
        broadcastEvent(id, TaskConstants.EVENT_CANCELLED);
        serviceCleanup(false);
    }

    // Cancel all threads, truncate local db, TODO: delete call to server
    public void cancelAll() {
        removeAllFromTaskPool();
        mTaskCache.removeAll();
        serviceCleanup(false);
    }

    public void retryTask(String taskId) {
        if (sTaskPool.containsKey(taskId)) {
            // If the task pool contains the id, that means it's already been retried
            return;
        }
        T task = mTaskCache.get(taskId);
        broadcastEvent(taskId, TaskConstants.EVENT_RETRYING);
        if (task != null) {
            if (mLoggingInterface != null) {
                mLoggingInterface.logTaskRetry(task);
            }
            // Run the task again
            addTask(task, true);
        } else {
            // The task that we're trying to retry isn't in the local db. That shouldn't be possible
            // so we should log it.
            TaskLogger.e("Attempt to retry an task that doesn't exist");
        }
    }
    // </editor-fold>

    /*
     * -----------------------------------------------------------------------------------------------------
     * Task Pool Management (pause/resume)
     * -----------------------------------------------------------------------------------------------------
     */
    // <editor-fold desc="Task Pool Management (pause/resume)">
    protected static void removeAllFromTaskPool() {
        for (Future future : sTaskPool.values()) {
            future.cancel(true);
        }
        sTaskPool.clear();
    }

    protected static void removeFromTaskPool(String id) {
        if (sTaskPool.containsKey(id)) {
            Future taskFuture = sTaskPool.get(id);
            taskFuture.cancel(true);
            sTaskPool.remove(id);
        }
    }

    public void userPauseAll() {
        mIsPaused = true;
        mTaskPreferences.setIsPaused(true);
        pauseAll();
        broadcastEvent(TaskConstants.EVENT_ALL_TASKS_PAUSED);
    }

    public void userResumeAll() {
        if (!mIsPaused) {
            return;
        }

        mIsPaused = false;
        mTaskPreferences.setIsPaused(false);
        if (resumeAll()) {
            broadcastEvent(TaskConstants.EVENT_ALL_TASKS_RESUMED);
        }
    }

    // Will resume if we're not already running - this is just a check that can be made to ensure everything
    // is running correctly
    public void resumeAllIfNecessary() {
        TaskLogger.d("Resume all if necessary");
        // TODO: Make sure taskpool only ever includes currently running tasks 11/5/15 [KV]
        // Also what if it's in the process of pausing when we go to resume (threading issue?)
        if (!sTaskPool.isEmpty()) {
            TaskLogger.d("Resuming all wasn't necessary");
            // If it's already resumed or the task pool has tasks running, don't bother trying to resume
            return;
        }
        resumeAll();
    }

    public void retryAllFailed() {
        for (Map.Entry<String, T> entry : getTasks().entrySet()) {
            if (entry.getValue().isError()) {
                retryTask(entry.getKey());
            }
        }
    }

    private void pauseForNetwork() {
        TaskLogger.d(LOG_TAG, "Pause for network");
        broadcastEvent(TaskConstants.EVENT_NETWORK_LOST);
        pauseAll();
    }

    private void resumeForNetwork() {
        TaskLogger.d(LOG_TAG, "Resume for network");
        if (resumeAll()) {
            broadcastEvent(TaskConstants.EVENT_NETWORK_RETURNED);
        }
    }

    private static void pauseAll() {
        // Issues interrupts to all threads
        for (Future future : sTaskPool.values()) {
            future.cancel(true);
        }
        sTaskPool.clear();
    }

    // Returns if it was able to actually resume
    // Won't resume if currently paused or no network (or if it's already resuming)
    private boolean resumeAll() {
        if (broadcastIsManagerSuspended() || isResuming) {
            // If we're paused or don't actually have network, broadcast that state and don't continue
            return false;
        }
        isResuming = true;
        // Only resume for network if the tasks aren't paused and it's not in the process of resuming
        // Issues interrupts to all threads
        for (Future future : sTaskPool.values()) {
            future.cancel(true);
        }
        // Clear the task pool because all the necessary tasks will be re-added
        sTaskPool.clear();
        // TODO: iterate through all threads with the task id and stop them 11/5/15 [KV]
        // I've seen threads survive the service dying
        // http://stackoverflow.com/questions/6667496/get-reference-to-thread-object-from-its-id

        // We then re-add all these unfinished tasks
        for (T task : mTaskCache.getTasksToRun()) {
            addTask(task, true);
        }
        isResuming = false;

        return true;
    }

    // This returns if it's possible to resume/start a task
    // It will broadcast the current state if it can't execute
    private boolean broadcastIsManagerSuspended() {
        boolean isSuspended = false;
        // TODO: Update the notification 11/6/15 [KV]
        if (mIsPaused) {
            isSuspended = true;
            broadcastEvent(TaskConstants.EVENT_NETWORK_LOST);
        } else if (!hasNetwork()) {
            isSuspended = true;
            broadcastEvent(TaskConstants.EVENT_NETWORK_LOST);
        }
        return isSuspended;
    }

    // </editor-fold>

    /*
     * -----------------------------------------------------------------------------------------------------
     * Service Management
     * -----------------------------------------------------------------------------------------------------
     */
    // <editor-fold desc="Service Management">

    /**
     * This service has side effects - if service should die, it'll kill it
     * Otherwise it will do some TaskPool and DB cleanup
     *
     * @param taskCompleted If we're calling serviceCleanup
     *                      because a task was completed
     */
    private void serviceCleanup(boolean taskCompleted) {
        // Check if there's anything in the db that needs to run
        if (!tasksRemaining()) {
            // If no tasks in the cache (set to shouldRun) or task pool, kill the service
            killService(taskCompleted);
        } else {
            boolean addTaskCalled = false;
            // There are still tasks that need to be run, so if
            // they're not already running (not in the task pool)
            // then let's kick them off 2/29/16 [KV]
            for (T task : getTasksToRun()) {
                if (!isInTaskPool(task.getId())) {
                    // If there is an unfinished task that isn't in the task pool, we'll have to add it
                    addTask(task, true);
                    addTaskCalled = true;
                }
            }
            if (!addTaskCalled) {
                // If we added a task, startService has already been called.
                // If we didn't add a task, we should still issue a call to
                // start the service in the event that a state change requires
                // the service to update start (state change could have caused a
                // change to the return value of `shouldRunTask` 3/1/16 [KV]
                startService();
            }
        }
    }

    public void startService() {
        // Call this method when we know the service should be running
        // (we just added a task or we know there are unfinished tasks).
        Intent startServiceIntent = new Intent(mContext, getServiceClass());
        mContext.startService(startServiceIntent);
    }

    private void killService(boolean taskCompleted) {
        if (taskCompleted) {
            broadcastEvent(TaskConstants.EVENT_ALL_TASKS_FINISHED);
        } else {
            broadcastEvent(TaskConstants.EVENT_KILL_SERVICE);
        }
    }
    // </editor-fold>

    /*
     * -----------------------------------------------------------------------------------------------------
     * Preferences
     * -----------------------------------------------------------------------------------------------------
     */
    // <editor-fold desc="Preferences">
    public boolean wifiOnly() {
        return mTaskPreferences.wifiOnly();
    }

    public void setWifiOnly(boolean wifiOnly) {
        mTaskPreferences.setWifiOnly(wifiOnly);
        if (!wifiOnly) {
            resumeAllIfNecessary();
        }
    }
    // </editor-fold>

    /*
     * -----------------------------------------------------------------------------------------------------
     * Network
     * -----------------------------------------------------------------------------------------------------
     */
    // <editor-fold desc="Network">
    public NetworkUtil getNetworkUtil() {
        return mNetworkUtil;
    }

    public boolean hasNetwork() {
        return mNetworkUtil.isConnected();
    }

    @Override
    public void onNetworkChange(boolean isConnected) {
        TaskLogger.d(LOG_TAG, "Network change");
        // Only resume if the connection changes to connected and wasn't previously connected
        // But always pause even if it's already paused
        if (isConnected) {
            // Don't cancel all threads
            resumeForNetwork();
        } else {
            pauseForNetwork();
        }
    }
    // </editor-fold>

    /*
     * -----------------------------------------------------------------------------------------------------
     * Logging
     * -----------------------------------------------------------------------------------------------------
     */
    // <editor-fold desc="Logging">
    protected void logSuccess(T task) {
        if (mLoggingInterface != null) {
            mLoggingInterface.logTaskSuccess(task);
        }
    }

    protected void logFailure(T task, TaskError error) {
        if (mLoggingInterface != null) {
            mLoggingInterface.logTaskFailure(task, error);
        }
    }
    // </editor-fold>

    /*
     * -----------------------------------------------------------------------------------------------------
     * Broadcast
     * -----------------------------------------------------------------------------------------------------
     */
    // <editor-fold desc="Broadcast">
    private String getBroadcastString() {
        return TaskConstants.TASK_BROADCAST + "_" + getManagerName();
    }

    public void registerReceiver(@NonNull BroadcastReceiver receiver) {
        LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(mContext);
        localBroadcastManager.registerReceiver(receiver, new IntentFilter(getBroadcastString()));
    }

    // Entire pool based events (paused, resumed)
    public void broadcastEvent(@NonNull String event) {
        Intent localIntent = new Intent(getBroadcastString());
        localIntent.putExtra(TaskConstants.TASK_EVENT, event);
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(localIntent);
    }

    // Task based events (added, succeeded, failed)
    private void broadcastEvent(@NonNull String id, @NonNull String event) {
        Intent localIntent = new Intent(getBroadcastString());
        localIntent.putExtra(TaskConstants.TASK_EVENT, event);
        localIntent.putExtra(TaskConstants.TASK_ID, id);
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(localIntent);
    }

    private void broadcastFailure(@NonNull String id, @NonNull TaskError error) {
        Intent localIntent = new Intent(getBroadcastString());
        localIntent.putExtra(TaskConstants.TASK_EVENT, TaskConstants.EVENT_FAILURE);
        localIntent.putExtra(TaskConstants.TASK_ID, id);
        localIntent.putExtra(TaskConstants.TASK_ERROR, error);
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(localIntent);
    }

    // Exclusively to relay progress for a task
    private void broadcastProgress(@NonNull String id, int progress) {
        Intent localIntent = new Intent(getBroadcastString());
        localIntent.putExtra(TaskConstants.TASK_EVENT, TaskConstants.EVENT_PROGRESS);
        localIntent.putExtra(TaskConstants.TASK_PROGRESS, progress);
        localIntent.putExtra(TaskConstants.TASK_ID, id);
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(localIntent);
    }
    // </editor-fold>
}