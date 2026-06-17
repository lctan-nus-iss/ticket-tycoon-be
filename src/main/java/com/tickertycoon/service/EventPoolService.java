package com.tickertycoon.service;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.tickertycoon.agent.EventAgent;
import com.tickertycoon.dto.EventDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Service
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
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

    public void startOnApplicationStartup() {
        if (!enabled) {
            log.info("[EventPoolService] Startup event pool warmup disabled");
            return;
        }

        log.info("[EventPoolService] Starting event pool warmup with targetSize={} refillThreshold={}",
            targetSize, refillThreshold);
        replenishPool("startup", targetSize);
        log.info("[EventPoolService] Startup warmup complete, pool size={}", preGeneratedEvents.size());
    }

    public EventDTO getNextEvent(int quarter, int year, String macroContext,
                                 String recentEventNames, String constraints) {
        if (!enabled) {
            log.info("[EventPoolService] Event pool disabled, generating event directly for Q{}Y{}", quarter, year);
            return eventAgent.generateFreshEvent(quarter, year, macroContext, recentEventNames, constraints);
        }

        EventDTO cached = pollPreGeneratedEvent();
        if (cached != null) {
            log.info("[EventPoolService] Serving cached event '{}'", cached.getName());
            scheduleRefillIfNeeded();
            return cached;
        }

        log.warn("[EventPoolService] Pool empty during request, doing blocking refill before serving event");
        replenishPool("blocking request refill", Math.max(refillThreshold + 1, 1));

        cached = pollPreGeneratedEvent();
        if (cached != null) {
            log.info("[EventPoolService] Serving cached event '{}' after blocking refill", cached.getName());
            scheduleRefillIfNeeded();
            return cached;
        }

        log.warn("[EventPoolService] Blocking refill produced no cached events, falling back to direct generation");
        return eventAgent.generateFreshEvent(quarter, year, macroContext, recentEventNames, constraints);
    }

    public EventDTO pollPreGeneratedEvent() {
        if (!enabled) {
            return null;
        }

        int beforeSize = preGeneratedEvents.size();
        
        log.info("[EventPoolService] Dispensing pre-generated event '{}' (before={}, remaining={})", beforeSize, preGeneratedEvents.size());

        EventDTO cached = preGeneratedEvents.poll();
        if (cached != null) {
            log.info("[EventPoolService] Dispensing pre-generated event '{}' (before={}, remaining={})",
                cached.getName(), beforeSize, preGeneratedEvents.size());
        } else {
            log.info("[EventPoolService] No cached event available to dispense (poolSize={})", beforeSize);
        }
        return cached;
    }

    public int getPoolSize() {
        return preGeneratedEvents.size();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isReplenishing() {
        return replenishing.get();
    }

    public int getTargetSize() {
        return targetSize;
    }

    public int getRefillThreshold() {
        return refillThreshold;
    }

    public void scheduleRefillIfNeeded() {
        if (!enabled || preGeneratedEvents.size() > refillThreshold) {
            return;
        }

        taskExecutor.execute(() -> replenishPool("refill", targetSize));
    }

    private void replenishPool(String reason, int desiredSize) {
        if (!replenishing.compareAndSet(false, true)) {
            log.info("[EventPoolService] Refill already in progress, skipping {}", reason);
            return;
        }

        try {
            int missing = Math.max(0, desiredSize - preGeneratedEvents.size());
            if (missing == 0) {
                return;
            }

            log.info("[EventPoolService] Replenishing {} event(s) for {}", missing, reason);
            for (int i = 0; i < missing; i++) {
                int sequence = preGeneratedEvents.size() + i;
                int quarter = (sequence % 4) + 1;
                int year = (sequence / 4) + 1;
                String macroContext = STARTUP_MACRO_CONTEXTS.get(sequence % STARTUP_MACRO_CONTEXTS.size());

                EventDTO event = eventAgent.generateFreshEvent(
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
