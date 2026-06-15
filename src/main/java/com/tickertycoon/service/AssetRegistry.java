package com.tickertycoon.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Single source of truth for all assets — mirrors the frontend data/assets.ts.
 * Used by GameService for price engine and income calculations.
 */
public class AssetRegistry {

    public record AssetDef(
        String  id,
        String  name,
        String  ticker,
        String  group,       // "stock" | "reit" | "etf"
        String  sector,      // Technology, Finance, etc.
        String  region,      // for regional ETFs
        double  basePrice,
        double  volatility,
        double  dividendRate, // quarterly rate
        boolean canBankrupt
    ) {}

    public static final List<AssetDef> ALL_ASSETS = Stream.of(
        // ── Individual Stocks ──
        new AssetDef("nvx",  "Nexovix Corp",        "NVX",  "stock","Technology", null, 80,  .22, .004, true),
        new AssetDef("qntm", "Quantum Leap Tech",   "QNTM", "stock","Technology", null, 55,  .28, 0,    true),
        new AssetDef("cldx", "CloudexaInc",         "CLDX", "stock","Technology", null, 120, .18, .006, true),
        new AssetDef("prm",  "Primefall Bank",      "PRM",  "stock","Finance",    null, 65,  .15, .025, true),
        new AssetDef("vlt",  "Vaultex Financial",   "VLT",  "stock","Finance",    null, 90,  .13, .020, true),
        new AssetDef("drx",  "Duralex Energy",      "DRX",  "stock","Energy",     null, 45,  .25, .030, true),
        new AssetDef("solv", "Solvara Renewables",  "SOLV", "stock","Energy",     null, 70,  .20, .008, true),
        new AssetDef("medx", "MedxPharma",          "MEDX", "stock","Healthcare", null, 110, .17, .012, true),
        new AssetDef("biov", "BioVance Labs",       "BIOV", "stock","Healthcare", null, 40,  .30, 0,    true),
        new AssetDef("luxe", "LuxeRetail Group",    "LUXE", "stock","Consumer",   null, 75,  .18, .018, true),
        new AssetDef("groc", "FreshMart Holdings",  "GROC", "stock","Consumer",   null, 88,  .10, .022, true),
        new AssetDef("trns", "TransCore Logistics", "TRNS", "stock","Industrial", null, 95,  .14, .016, true),
        // ── Individual REITs ──
        new AssetDef("nxdc", "NexaCore Data REIT",     "NXDC", "reit","Data Centre",null, 70,  .12, .040, true),
        new AssetDef("apex", "Apex Tower REIT",        "APEX", "reit","Office",     null, 50,  .18, .055, true),
        new AssetDef("vrdx", "VerdaHealth REIT",       "VRDX", "reit","Healthcare", null, 85,  .08, .042, true),
        new AssetDef("plzx", "PlazioCentre REIT",      "PLZX", "reit","Mall",       null, 35,  .22, .068, true),
        new AssetDef("logx", "LogiPark REIT",          "LOGX", "reit","Industrial", null, 92,  .10, .036, true),
        new AssetDef("svrx", "SilverLane Office REIT", "SVRX", "reit","Office",     null, 42,  .20, .060, true),
        new AssetDef("carx", "Carevera Health REIT",   "CARX", "reit","Healthcare", null, 78,  .09, .044, true),
        new AssetDef("whrx", "Warehouse Prime REIT",   "WHRX", "reit","Industrial", null, 105, .11, .032, true),
        // ── Regional Equity ETFs ──
        new AssetDef("etf_us",     "AmeriCore 500 ETF",   "AMC",  "etf", null, "America",          100, .09, .015, false),
        new AssetDef("etf_ustech", "TechVault US ETF",    "TVU",  "etf", null, "America Tech",     100, .14, .004, false),
        new AssetDef("etf_cn",     "ChinaDragon ETF",     "CDX",  "etf", null, "China Tech",       100, .20, .008, false),
        new AssetDef("etf_asia",   "AsiaPac ex-China ETF","APX",  "etf", null, "Asia-Pac ex-China",100, .13, .018, false),
        new AssetDef("etf_eu",     "EuroStar Broad ETF",  "ESB",  "etf", null, "Europe",           100, .11, .020, false),
        // ── Other ETFs ──
        new AssetDef("bonds", "SafeHaven Bond ETF","SHB",  "etf", "Bonds",      null, 100, .04, .012, false),
        new AssetDef("reitx", "AllProp REIT ETF",  "ALLP", "etf", "REIT",       null, 100, .10, .030, false),
        new AssetDef("gold",  "GoldVault ETF",     "GVT",  "etf", "Commodity",  null, 100, .07, 0,    false),
        new AssetDef("silver","Silver Ridge ETF",  "SVR",  "etf", "Commodity",  null, 100, .13, 0,    false),
        new AssetDef("oil",   "BrentWave Oil ETF", "BWO",  "etf", "Commodity",  null, 100, .22, 0,    false)
    ).collect(Collectors.toList());

    private static final Map<String, AssetDef> INDEX =
        ALL_ASSETS.stream().collect(Collectors.toMap(AssetDef::id, Function.identity()));

    public static AssetDef find(String id) { return INDEX.get(id); }
}
