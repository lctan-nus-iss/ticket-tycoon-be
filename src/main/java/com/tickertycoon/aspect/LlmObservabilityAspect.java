package com.tickertycoon.aspect;

import com.tickertycoon.port.LlmRequest;
import com.tickertycoon.port.LlmResponse;
import lombok.extern.log4j.Log4j2;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import java.util.Map;

@Aspect
@Component
@Log4j2
public class LlmObservabilityAspect {

    /** Cost in USD per 1M tokens: [inputPrice, outputPrice] */
    private static final Map<String, double[]> PRICING = Map.of(
        "claude-haiku-4-5-20251001",  new double[]{0.80,   4.00},
        "claude-sonnet-4-20250514",   new double[]{3.00,  15.00},
        "gpt-4o",                     new double[]{2.50,  10.00},
        "gpt-4o-mini",                new double[]{0.15,   0.60},
        "gemini-2.0-flash",           new double[]{0.075,  0.30},
        "deepseek-chat",              new double[]{0.27,   1.10},
        "deepseek-reasoner",          new double[]{0.55,   2.19}
    );

    @Around("execution(* com.tickertycoon.router.LlmRouter.complete(..))")
    public Object observe(ProceedingJoinPoint pjp) throws Throwable {
        LlmRequest  req  = (LlmRequest)  pjp.getArgs()[0];
        LlmResponse resp = (LlmResponse) pjp.proceed();

        double[] price = PRICING.getOrDefault(resp.getModel(), new double[]{0, 0});
        double cost = (resp.getInputTokens()  / 1_000_000.0 * price[0])
                    + (resp.getOutputTokens() / 1_000_000.0 * price[1]);

        log.info("[LLM_COST] agent={} provider={} model={} in_tokens={} out_tokens={} latency_ms={} estimated_usd={:.6f}",
            req.getAgentName(), resp.getProvider(), resp.getModel(),
            resp.getInputTokens(), resp.getOutputTokens(),
            resp.getLatencyMs(), cost);

        return resp;
    }
}
