# turnstile
Turnstile is an abstract task queue that supports long running, parallel task execution. Turnstile let's you define your own stateful tasks and manage their transition from state to state.

## Contents
* [Features](#features)
* 
**Consider deleting the "why" and "what else" sections. It's sufficient to just describe it's features/benefits imo. Readers can diff the feature set against that of other libraries. The "what else" section will become out of date.**

* [Why Write Another Task Queue?](#why-write-another-task-queue)
    - [What Else Is Out There?](#what-else-is-out-there)
* [Use Cases](#use-cases)
* [Getting Started](#getting-started)
    - [Gradle](#gradle)
    - [Submodule](#submodule)

**Why slash API? Can we remove the slash, it's unclear what it means. Reading through the section itself it feels like it's doing too much. Can it be broken apart into smaller chunks?**

* [Example/API](#example)  
* [Contact Us](#contact-us)
    - [Found an Issue?](#found-an-issue)
    - [Want to Contribute?](#want-to-contribute)
    - [Questions?](#questions)
* [License](#license)

## Features

**Consider changing this form a bullet list to complete sentences broken into a few short paragraphs.**

* Long running background task execution through a [Service](https://developer.android.com/reference/android/app/Service.html)
    - Automatically resume tasks after your app is killed or the device is restarted
    - Highly customizable notification (optional)
* Parallel processing
    - Ability to specify number of concurrent tasks
* Disk backed task cache
* Progress broadcasting
* Specify conditions necessary to run your task
    - Ex. Automatically pause when network is lost and resume when network has returned
* Customizable

**If this section remains, consider just calling it something like "Background" and moving it to the top just below the TOC.**

## Why Write Another Task Queue?
Although there are many phenomenal task queue libraries that exist for Android, we created this one to accommodate a broader set of requirements. While implementing upload and download for Vimeo, we found we needed more control over the state of our tasks which other libraries couldn't accommodate. For upload, we didn't want to persist the tasks to disk until the user hit a button, but we still wanted it to execute. For download, we wanted a customizable app drawer notification and the ability to store more objects with our task (like our concrete Video object). And for both queues, we wanted to support complex retry logic that allowed us to pick up where we left off so that if our upload or download was interrupted at 80%, it would be able to continue from that point without needing to start over.

There are many common [features](#features) that every task queue will have, but you may still want the flexibilty to determine how, why, and when your tasks are run. This library provides that flexibility while removing a majority of the boilerplate. It's easy to use for a simple case and extensible for a more custom [use case](#use-cases).

### What Else Is Out There?
If you don't think you'll benefit from the customizability of this library, there are other options that each provide different advantages (this list is not exhaustive).

* android-priority-jobqueue
    - Pros: Most of the [features above](#features). Additionally has prioritization of jobs, job delay, load balancing, and grouping
    - Cons: Doesn't run in a Service (dies with your application). It's difficult to extend or customize.
* tape
    - Pros: Most of the [features above](#features). Very easy to implement for simple tasks.
    - Cons: No progress broadcasting or awareness of network. It's difficult to extend or customize.
* robospice
    - Pros: Good for long running network requests.
    - Cons: It's difficult to extend or customize. It also has a limited feature set.

**This seems redundant to me, or unnecessary. We do/can state the use cases for Vimeo in the "Background" section. Beyond that, users can brainstorm applications themselves?**

## Use Cases
* Upload/download
* Image/video processing
* Batching important network requests when offline
    - Analytics
    - Messaging applications
* Long running background server syncing

## Getting Started
For a more in depth look at the usage, refer to the [example Android app](example). The example project includes implementation of all of the below features.

#### Gradle
Specify the dependency in your `build.gradle` file (make sure `jcenter()` is included as a repository)
```groovy
compile 'com.vimeo.turnstile:turnstile:0.8.0'
```

#### Submodule
We recommend using JCenter, but if you'd like to use the library as a submodule:
```
git submodule add git@github.com:vimeo/turnstile-android.git
```
Then in your `build.gradle` use:
```groovy
compile project(':turnstile-android:turnstile')
```

## Example

#### API

All examples below are in reference to the code found in the [sample app](sample/src/main/java/com/vimeo/sample). It is encouraged that you copy the java classes from the sample folder into your app to help get started. Refer to the [initialization section](#initialization) to continue getting set up. API explanation will reference the sample app, please see that code to understand it fully.

The API consists of 3 main classes that you will need to extend, `BaseTaskManager`, `BaseTaskService`, and `BaseTask`; and two optional classes you can supply for more functionality, `TaskLogger` and `Conditions`.

- `BaseTaskManager`: This is the class responsible for running the tasks and tying everything together. You must extend this in order to provide any additional task handling logic and convenience APIs. Your task manager must be a singleton, since a reference is required by your `BaseTaskService` class. See below for more information on initialization.
 
- `BaseTask`: The task that you wish to run. All your work will be done in the `execute()` method on a background thread. You should call task lifecycle events where appropriate, such as `onTaskProgress(int progress)`, `onTaskFailure(TaskError error)`, and `onTaskCompleted`.

- `BaseTaskService`: This is the service that the `BaseTaskManager` will be held by and on which all work will be done. You must extend this class and supply your `BaseTaskManager` instance. If you wish to have notifications, extend `NotificationTaskService` instead, and configure notification setup data such as icons, strings, and ids.

- `TaskLogger`: TaskLogger is used to log messages and debugging information by the library and by default uses `DefaultLogger`, which uses `android.util.Log`. If you want to provide your own logging solution, or turn off or on certain logs, you can supply your own logger by implementing the `TaskLogger.Logger` interface and supplying that to `TaskLogger.setLogger(Logger logger)`.

- `Conditions`: This is an interface used by the library to determine whether or not the device conditions are suitable to run the tasks, e.g. network availability. You can use one of the default ones supplied, such as `NetworkConditionsBasic`, which checks for network connectivity, or `NetworkConditionsExtended`, which checks for wifi connectivity. You can also extend `NetworkConditions` to create your own network based conditions, or go completely custom by implementing your own `Conditions`, e.g. you don't want to run tasks if the battery is too low.

The `BaseTaskManager` controls the execution of the `BaseTask`s as specified by the `Conditions`. A singleton reference to the `BaseTaskManager` is held by the `BaseTaskService` to ensure that it isn't garbage collected so it can be as resilient as possible.

#### Initialization

##### Constructing `BaseTaskManager`

Your `BaseTaskManager` should be a singleton, and must also be constructed by injecting the `BaseTaskManager.Builder` class into the constructor. See the Sample app for a [simple implementation](https://github.com/vimeo/turnstile-android/blob/master/sample/src/main/java/com/vimeo/sample/tasks/SimpleTaskManager.java) of this: 

```java
@Nullable
private static SimpleTaskManager sInstance;

@NonNull
public synchronized static SimpleTaskManager getInstance() {
    if (sInstance == null) {
        throw new IllegalStateException("Must be initialized first");
    }
    return sInstance;
}

public static void initialize(@NonNull Builder builder) {
    sInstance = new SimpleTaskManager(builder);
}
```

Initialize the manager in the `onCreate()` of your Application class: 

```java
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

    Intent intent = new Intent(this, MainActivity.class);
    intent.setAction(NOTIFICATION_INTENT_KEY);

    taskTaskManagerBuilder.withNotificationIntent(intent);

    SimpleTaskManager.initialize(taskTaskManagerBuilder);
}
```

Then the task manager can be referenced via `SimpleTaskManager.getInstance()`.

##### Declaring `BaseTaskService`

In order for the service to run correctly, you must declare your implementation of `BaseTaskService` in your app's manifest:

```xml
<service
    android:name=".tasks.SimpleTaskService"
    android:enabled="true"
    android:exported="false"/>
```

##### Setting the `TaskLogger`

During initialization of the library (or anytime you want), you can change the task logger used:

```java
// Use our own task logger
TaskLogger.setLogger(new SimpleLogger());
```

##### Creating `BaseTask`

`BaseTask` is serialized using Gson, so by default, fields you add to your implementation of `BaseTask` will be stored and retrieved when the library is initialized. If you don't want something to be saved, mark it using the java keyword `transient` and it will not be saved. Each task must also have a unique id. If you are using this library for something like file downloads, then the URI is a great candidate for this unique id. However, if you don't have anything that would serve as a good id, then you can just use the `UniqueIdGenerator` supplied by the library that uses Java's `UUID` class, by passing `UniqueIdGenerator.generateId()` into the task constructor.

##### Adding and listening to tasks

Now that everything has been set up, you want to add a task and listen to its lifecycle events while your app is alive. You can use this to relay task events including task progress, failure, completion, and others:

```java
SimpleTaskManager taskManager = SimpleTaskManager.getInstance();
// Add a listener to receive notification of task related events
taskManager.registerTaskEventListener(new TaskEventListener<SimpleTask>() {
    @Override
    public void onStarted(@NonNull SimpleTask task) {
        // a task has started
    }

    @Override
    public void onFailure(@NonNull SimpleTask task, @NonNull TaskError error) {
        // a task has failed
    }

    @Override
    public void onCanceled(@NonNull SimpleTask task) {
        // a task has been canceled
    }

    @Override
    public void onSuccess(@NonNull SimpleTask task) {
        // a task has succeeded
    }

    @Override
    public void onAdded(@NonNull SimpleTask task) {
        // a task has been added
    }
});

// Add a listener to receive notification of manager specific events
taskManager.registerManagerEventListener(new ManagerEventListener() {
    @Override
    public void onAllTasksPaused() {
        // all tasks have been paused
    }

    @Override
    public void onAllTasksResumed() {
        // all tasks have been resumed
    }

    @Override
    public void onConditionsLost() {
        // the conditions have changed and been lost (e.g. network connectivity lost)
    }

    @Override
    public void onConditionsReturned() {
        // the conditions have changed and are favorable (e.g. network connectivity regained)
    }
});

SimpleTask task = new SimpleTask(UniqueIdGenerator.generateId());
taskManager.addTask(task, new TaskCallback() {
    @Override
    public void onSuccess() {
        // the task was added successfully
    }

    @Override
    public void onFailure(@NonNull Exception exception) {
        // the task couldn't be added
    }
});

```

## Contact US

#### Found an Issue?
Please file it in the git [issue tracker](https://github.com/vimeo/turnstile-android/issues).

#### Want to Contribute?
If you'd like to contribute, please follow our guidelines found in [CONTRIBUTING.md](CONTRIBUTING.md).

#### Questions?
Tweet at us here: [@vimeoapi](https://twitter.com/vimeoapi).

Post on [Stackoverflow](http://stackoverflow.com/questions/tagged/vimeo-android) with the tag `vimeo-android`.

Get in touch [here](https://vimeo.com/help/contact).

Interested in working at Vimeo? We're [hiring](https://vimeo.com/jobs)!

## License
`turnstile-android` is available under the MIT license. See the [LICENSE](LICENSE) file for more info.
