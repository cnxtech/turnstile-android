package com.vimeo.sample;

import android.app.Application;
import android.content.Intent;

import com.vimeo.sample.tasks.SimpleConditions;
import com.vimeo.sample.tasks.SimpleLoggingInterface;
import com.vimeo.sample.tasks.SimpleTask;
import com.vimeo.sample.tasks.SimpleTaskManager;
import com.vimeo.turnstile.TaskManagerBuilder;

public class App extends Application {

    public static final String NOTIFICATION_INTENT_KEY = "NOTIFICATION";

    @Override
    public void onCreate() {
        super.onCreate();

        TaskManagerBuilder<SimpleTask> taskTaskManagerBuilder = new TaskManagerBuilder<>(this);
        taskTaskManagerBuilder.withLoggingInterface(new SimpleLoggingInterface());
        taskTaskManagerBuilder.withConditions(new SimpleConditions());

        // We could also use the built in NetworkConditionsBasic class
        // taskTaskManagerBuilder.withConditions(new NetworkConditionsBasic(this));

        // Or we could use the built in NetworkConditionsExtended class
        // taskTaskManagerBuilder.withConditions(new NetworkConditionsExtended(this));

        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(NOTIFICATION_INTENT_KEY);

        taskTaskManagerBuilder.withNotificationIntent(intent);

        SimpleTaskManager.initialize(taskTaskManagerBuilder);
    }
}