package com.vimeo.download_sample;

import android.app.Application;
import android.content.Intent;

import com.vimeo.download_sample.tasks.SimpleConditions;
import com.vimeo.download_sample.tasks.SimpleLogger;
import com.vimeo.download_sample.tasks.SimpleTaskManager;
import com.vimeo.turnstile.BaseTaskManager;
import com.vimeo.turnstile.utils.TaskLogger;

public class App extends Application {

    public static final String NOTIFICATION_INTENT_KEY = "NOTIFICATION";

    @Override
    public void onCreate() {
        super.onCreate();

        // Inject the components we want into the TaskManager
        BaseTaskManager.Builder taskTaskManagerBuilder = new BaseTaskManager.Builder(this);
        taskTaskManagerBuilder.withConditions(new SimpleConditions());
        taskTaskManagerBuilder.withStartOnDeviceBoot(false);

        // We could also use the built in NetworkConditionsBasic class
        // taskTaskManagerBuilder.withConditions(new NetworkConditionsBasic(this));

        // Or we could use the built in NetworkConditionsExtended class
        // taskTaskManagerBuilder.withConditions(new NetworkConditionsExtended(this));

        // Use our own task logger
        TaskLogger.setLogger(new SimpleLogger());

        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(NOTIFICATION_INTENT_KEY);

        taskTaskManagerBuilder.withNotificationIntent(intent);

        SimpleTaskManager.initialize(taskTaskManagerBuilder);
    }
}
