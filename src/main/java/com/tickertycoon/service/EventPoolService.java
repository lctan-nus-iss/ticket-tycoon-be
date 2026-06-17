package com.tickertycoon.service;

import com.tickertycoon.agent.EventAgent;
import com.tickertycoon.dto.EventDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Log4j2
public class EventPoolService {

    private static final List<String> STARTUP_MACRO_CONTEXTS = List.of(
        "global trade war escalating with broad tariff hikes",
        "a global pandemic is emerging, borders are closing",
        "global supply chains are severely disrupted",
        "the US dollar is weakening significantly",
        "global interest rates are rising sharply",
        "a global recession is being forecast",
        "inflation is running hot across major economies",
        "war has broken out in Europe threatening energy supplies",
        "China-Taiwan strait tensions are at a 20-year high",
        "China is cracking down on its domestic technology sector",
        "the US Federal Reserve is cutting interest rates",
        "strong US corporate earnings are beating expectations",
        "China is announcing major economic stimulus measures",
        "Japan is ending its negative interest rate policy",
        "AI investment is surging globally",
        "green energy transition policy is accelerating",
        "commodity prices are surging on supply shocks",
        "OPEC announces surprise production cuts"
    );

    private final EventAgent eventAgent;
    @Qualifier("taskExecutor")
    private final Executor taskExecutor;

    private final Queue<EventDTO> preGeneratedEvents = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean replenishing = new AtomicBoolean(false);

    @Value("${ticker-tycoon.event-pool.enabled:true}")
    private boolean enabled;

    @Value("${ticker-tycoon.event-pool.target-size:12}")
    private int targetSize;

    @Value("${ticker-tycoon.event-pool.refill-threshold:4}")
    private int refillThreshold;

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmPoolOnStartup() {
        if (!enabled) {
            log.info("[EventPoolService] Startup event pool warmup disabled");
            return;
        }

        replenishPool("startup");
    }

    public EventDTO nextEvent(int quarter, int year, String macroContext,
                              String recentEventNames, String constraints) {
        EventDTO cached = enabled ? preGeneratedEvents.poll() : null;
        if (cached != null) {
            log.info("[EventPoolService] Using pre-generated event '{}' (remaining={})",
                cached.getName(), preGeneratedEvents.size());
            scheduleRefillIfNeeded();
            return cached;
        }

        log.info("[EventPoolService] Pool empty, generating event on demand");
        return eventAgent.generateNext(quarter, year, macroContext, recentEventNames, constraints);
    }

    public int getPoolSize() {
        return preGeneratedEvents.size();
    }

    public void scheduleRefillIfNeeded() {
        if (!enabled || preGeneratedEvents.size() > refillThreshold) {
            return;
        }

        taskExecutor.execute(() -> replenishPool("refill"));
    }

    private void replenishPool(String reason) {
        if (!replenishing.compareAndSet(false, true)) {
            return;
        }

        try {
            int missing = Math.max(0, targetSize - preGeneratedEvents.size());
            if (missing == 0) {
                return;
            }

            log.info("[EventPoolService] Replenishing {} event(s) for {}", missing, reason);
            for (int i = 0; i < missing; i++) {
                int sequence = preGeneratedEvents.size() + i;
                int quarter = (sequence % 4) + 1;
                int year = (sequence / 4) + 1;
                String macroContext = STARTUP_MACRO_CONTEXTS.get(sequence % STARTUP_MACRO_CONTEXTS.size());

                EventDTO event = eventAgent.generateNext(
                    quarter,
                    year,
                    macroContext,
                    "none",
                    "create a reusable event for the shared startup pool"
                );
                preGeneratedEvents.offer(event);
            }

            log.info("[EventPoolService] Pool size is now {}", preGeneratedEvents.size());
        } finally {
            replenishing.set(false);
        }
    }
}
