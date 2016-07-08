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
package com.vimeo.taskqueue;

/**
 * Constants used throughout the library.
 * <p/>
 * Created by kylevenn on 2/23/16.
 */
public final class TaskConstants {

    public static final int NOT_FOUND = -1;

    public static final String TASK_BROADCAST = "TASK_BROADCAST_";

    // ---- Broadcasts ----
    public static final String TASK_EVENT = "TASK_EVENT";
    public static final String TASK_PROGRESS = "TASK_PROGRESS";
    public static final String TASK_ID = "TASK_ID";
    public static final String TASK_ERROR = "TASK_ERROR";

    // ---- Broadcast Events ----
    public static final String EVENT_PROGRESS = "EVENT_PROGRESS";
    public static final String EVENT_SUCCESS = "EVENT_SUCCESS";
    public static final String EVENT_FAILURE = "EVENT_FAILURE";
    public static final String EVENT_RETRYING = "EVENT_RETRYING";
    public static final String EVENT_ADDED = "EVENT_ADDED";
    public static final String EVENT_CANCELLED = "EVENT_CANCELLED";

    public static final String EVENT_RESUME_IF_NECESSARY = "EVENT_RESUME_IF_NECESSARY";
    public static final String EVENT_ALL_TASKS_FINISHED = "EVENT_ALL_TASKS_FINISHED";
    public static final String EVENT_KILL_SERVICE = "KILL_SERVICE";
    public static final String EVENT_NETWORK_LOST = "EVENT_NETWORK_LOST";
    public static final String EVENT_NETWORK_RETURNED = "EVENT_NETWORK_RETURNED";
    public static final String EVENT_ALL_TASKS_PAUSED = "EVENT_ALL_TASKS_PAUSED";
    public static final String EVENT_ALL_TASKS_RESUMED = "EVENT_ALL_TASKS_RESUMED";

    private TaskConstants() {
    }
}
