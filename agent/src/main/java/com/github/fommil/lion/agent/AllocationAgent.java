package com.github.fommil.lion.agent;

import com.google.common.collect.Maps;
import com.google.monitoring.runtime.instrumentation.AllocationInstrumenter;
import com.google.monitoring.runtime.instrumentation.AllocationRecorder;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.System.out;
import static java.util.concurrent.TimeUnit.SECONDS;

public class AllocationAgent {
    private static final ThreadFactory daemon = new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            return thread;
        }
    };

    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(daemon);

    public static void premain(String agentArgs, Instrumentation inst) {
        String[] args = agentArgs.split(" ");

        if (System.getProperty("java.version").contains("1.8"))
            throw new UnsupportedOperationException("Java 8: https://github.com/fommil/lions-share/issues/7");

        String filename = args[0];
        File outFile = new File(filename);
        if (outFile.delete())
            out.println("[AGENT] Deleted an existing " + outFile.getAbsolutePath());

        final Long period = Long.parseLong(args[1]);
        checkArgument(period > 0, "period must be greater than zero seconds");

        out.println("[AGENT] Writing allocation data to " + outFile.getAbsolutePath());
        out.println("[AGENT] Taking snapshots every " + period + " seconds");

        Map<String, Long> rates = Maps.newHashMap();
        if (args.length > 2)
            for (String arg : args[2].split(",")) {
                String[] parts = arg.split(":");
                Long sampleRate = Long.parseLong(parts[1]);
                checkArgument(!parts[0].contains(" "), parts[0] + " is not a valid type (spaces not allowed)");
                checkArgument(!parts[0].contains("."), parts[0] + " is not a valid type (replace dots with slashes)");
                out.println("[AGENT] " + parts[0] + " will be sampled every " + sampleRate + " bytes");
                rates.put(parts[0], sampleRate);
            }

        AllocationInstrumenter.premain(agentArgs, inst);
        final AllocationSampler sampler = new AllocationSampler(rates, rates.keySet());
        AllocationRecorder.addSampler(sampler);

        final AllocationPrinter printer = new AllocationPrinter(sampler, outFile);

        // HACK futile attempt to clear out stats about JVM internals...
        executor.schedule(new Runnable() {
            @Override
            public void run() {
                sampler.clear();
                executor.scheduleWithFixedDelay(printer, period, period, SECONDS);
            }
        }, period, SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // tries to get logs on shutdown
                printer.run();
            }
        });
    }

}
