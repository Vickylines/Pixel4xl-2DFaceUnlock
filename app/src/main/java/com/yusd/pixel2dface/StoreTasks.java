package com.yusd.pixel2dface;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-process disk queue; independent from camera teardown and never used in SystemUI. */
final class StoreTasks {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private StoreTasks() { }
    static void execute(Runnable work) { IO.execute(work); }
}
