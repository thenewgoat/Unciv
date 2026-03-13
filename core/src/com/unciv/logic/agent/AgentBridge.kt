package com.unciv.logic.agent

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessApplication
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.ruleset.RulesetCache
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.stats.Stat
import com.unciv.logic.map.MapSize
import com.unciv.logic.multiplayer.Multiplayer
import com.unciv.ui.audio.MusicController
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal bridge for Python (JPype) to control Unciv headlessly.
 *
 * Init pipeline (deterministic, no fallbacks):
 *   1. initHeadless()          — libGDX globals (Gdx.app, Gdx.files)
 *   2. initUncivSingleton()    — UncivGame.Current + settings + files
 *   3. initRulesets(assetsDir)  — RulesetCache with vanilla ruleset
 *
 * Call initOrThrow(assetsDir) once before createGame().
 */
object AgentBridge {

    const val VERSION = "0.10.0"  // disposeGame + configurable civs
    @JvmStatic fun getVersion(): String = VERSION

    @Volatile private var initialized: Boolean = false

    /**
     * One-shot init. assetsDir must contain jsons/ (e.g. .../android/assets).
     * Idempotent. Throws on any failure.
     */
    @JvmStatic fun initOrThrow(assetsDir: String) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initHeadlessOrThrow()
            initUncivSingletonOrThrow()
            initRulesetsOrThrow(assetsDir)
            initialized = true
        }
    }

    // ── Step 1: libGDX headless backend ──────────────────────────────────

    private fun initHeadlessOrThrow() {
        val cfg = HeadlessApplicationConfiguration()
        val listener = object : ApplicationListener {
            override fun create() {}
            override fun resize(width: Int, height: Int) {}
            override fun render() {}
            override fun pause() {}
            override fun resume() {}
            override fun dispose() {}
        }
        HeadlessApplication(listener, cfg)
        checkNotNull(Gdx.app) { "HeadlessApplication did not set Gdx.app" }
        checkNotNull(Gdx.files) { "HeadlessApplication did not set Gdx.files" }
    }

    // ── Step 2: Unciv singleton ──────────────────────────────────────────

    private fun initUncivSingletonOrThrow() {
        val game = UncivGame()
        UncivGame.Current = game
        game.settings = GameSettings()
        game.files = UncivFiles(Gdx.files)
        game.musicController = MusicController()
        game.onlineMultiplayer = Multiplayer()
        check(UncivGame.isCurrentInitialized()) { "UncivGame.Current not set" }
    }

    // ── Step 3: Rulesets ─────────────────────────────────────────────────

    private fun initRulesetsOrThrow(assetsDir: String) {
        // consoleMode=false → uses Gdx.files.internal() which reads from classpath (JAR).
        // HeadlessApplication must be initialized first so Gdx.files exists.
        // assetsDir is kept as parameter for future filesystem-based loading if needed.
        RulesetCache.loadRulesets(consoleMode = false, noMods = true)
        // Postcondition: vanilla ruleset must be loaded
        RulesetCache.getVanillaRuleset() // throws if missing
    }

    // ── Game lifecycle ───────────────────────────────────────────────────

    /**
     * Release references from the previous game so the JVM can GC it.
     * Call before createGame() or loadGameFromFile() to prevent heap leaks
     * across episodes.
     */
    @JvmStatic fun disposeGame(gameInfo: GameInfo?) {
        // Clear the UncivGame singleton's reference
        if (UncivGame.isCurrentInitialized()) {
            UncivGame.Current.gameInfo = null
        }
        // Hint to the GC (not guaranteed, but helps after large deallocations)
        System.gc()
    }

    @JvmStatic fun createGame(
        seed: Long,
        mapSize: String = "Tiny",
        difficulty: String = "Chieftain",
        playerCiv: String = "Rome",
        aiCiv: String = "Greece"
    ): GameInfo {
        check(initialized) { "Call initOrThrow(assetsDir) before createGame()" }

        val setup = GameSetupInfo()
        val params = setup.gameParameters

        params.players = arrayListOf(
            Player().apply {
                playerType = PlayerType.Human
                chosenCiv = playerCiv
            },
            Player().apply {
                playerType = PlayerType.AI
                chosenCiv = aiCiv
            }
        )

        params.numberOfCityStates = 0
        params.noBarbarians = true
        params.difficulty = difficulty
        setup.mapParameters.seed = seed
        setup.mapParameters.mapSize = MapSize(mapSize)
        setup.mapParameters.strategicBalance = true

        // Use bounded A* pathfinding to prevent AI unit automation from
        // doing unbounded turn-by-turn BFS on disconnected maps.
        UncivGame.Current.settings.useAStarPathfinding = true

        val gameInfo = GameStarter.startNewGame(setup)

        // Lock diplomacy: AI cannot offer/accept peace or change relationships
        gameInfo.ruleset.modOptions.uniques.add("Diplomatic relationships cannot change")

        // Some forks need explicit transient init
        try {
            gameInfo.javaClass.getMethod("setTransients").invoke(gameInfo)
        } catch (_: Throwable) {}

        return gameInfo
    }

    // ── Action result type ──────────────────────────────────────────────

    private sealed class ActionResult {
        data class Success(val message: String = "ok") : ActionResult()
        data class Error(val code: String, val message: String) : ActionResult()
    }

    /**
     * Extract game state as a JSON string (v4).
     *
     * Output schema:
     * {
     *   "global":       { turn, phase, war_state, gold, gold_per_turn, happiness, culture, culture_per_turn, faith, faith_per_turn, science_per_turn, era },
     *   "tech":         { current_research, turns_remaining, researched, available },
     *   "policies":     { adopted, available, can_adopt, culture_needed },
     *   "religion":     { status, religion_name, beliefs, available_pantheon_beliefs, available_follower_beliefs, available_founder_beliefs, available_enhancer_beliefs, can_found_pantheon, holy_city },
     *   "cities":       [ { id, name, owner, position, population, health, current_production, production_queue, food, food_stored, turns_to_grow, production_amount, gold_yield, science_yield, culture_yield, defense, buildings, can_bombard, bombard_range } ],
     *   "units":        [ { id, owner, type, hp, movement, position, is_civilian, strength, ranged_strength, range, promotions, available_promotions, experience, is_fortified, can_attack } ],
     *   "enemy_cities": [ { id, name, owner, position, population, health, defense } ],
     *   "enemy_units":  [ { id, owner, type, hp, movement, position, is_civilian, strength, ranged_strength, range, is_fortified } ],
     *   "map":          { width, height, tiles: [ { x, y, terrain, terrain_features, resource, owner, visible, explored, improvement, road, pillaged } ] },
     *   "events":       [ { type, text, icons } ]
     * }
     */
    @JvmStatic fun extractState(gameInfo: GameInfo): String {
        val playerCiv = gameInfo.civilizations.firstOrNull {
            it.playerType == PlayerType.Human
        }
        val json = JSONObject()
        json.put("global", extractGlobal(gameInfo, playerCiv))
        json.put("tech", extractTech(playerCiv))
        json.put("policies", extractPolicies(playerCiv))
        json.put("religion", extractReligion(playerCiv))
        json.put("cities", extractCities(playerCiv))
        json.put("units", extractUnits(playerCiv))
        json.put("enemy_cities", extractEnemyCities(playerCiv))
        json.put("enemy_units", extractEnemyUnits(playerCiv))
        json.put("map", extractMap(gameInfo, playerCiv))
        json.put("events", extractNotifications(playerCiv))
        return json.toString()
    }

    // ── Notifications ────────────────────────────────────────────────────

    /**
     * Extract current-turn notifications as a JSON array of event objects.
     *
     * Each notification becomes:
     *   { "type": <category>, "text": <text>, "icons": [<icon strings>] }
     */
    private fun extractNotifications(playerCiv: com.unciv.logic.civilization.Civilization?): JSONArray {
        val arr = JSONArray()
        if (playerCiv == null) return arr
        for (n in playerCiv.notifications) {
            val obj = JSONObject()
            obj.put("type", n.category.name)
            obj.put("text", n.text)
            val icons = JSONArray()
            for (icon in n.icons) {
                icons.put(icon)
            }
            obj.put("icons", icons)
            arr.put(obj)
        }
        return arr
    }

    // ── Save game to file (for debugging in Unciv GUI) ─────────────────

    /**
     * Save a GameInfo to a file that can be loaded by the Unciv desktop app.
     *
     * @param gameInfo  The current GameInfo instance.
     * @param filePath  Absolute path to write the save file.
     * @return JSON string: {"success": bool, "error_message"?: str, "path": str}
     */
    @JvmStatic fun saveGameToFile(gameInfo: GameInfo, filePath: String): String {
        check(initialized) { "Call initOrThrow(assetsDir) before saving" }
        return try {
            val gameString = UncivFiles.gameInfoToString(gameInfo, forceZip = false)
            java.io.File(filePath).writeText(gameString, Charsets.UTF_8)
            val result = JSONObject()
            result.put("success", true)
            result.put("path", filePath)
            result.toString()
        } catch (e: Exception) {
            val result = JSONObject()
            result.put("success", false)
            result.put("error_message", e.message ?: e.toString())
            result.put("path", filePath)
            result.toString()
        }
    }

    // ── Load game from file ────────────────────────────────────────────

    /**
     * Load a GameInfo from a previously saved file.
     *
     * @param filePath  Absolute path to the save file.
     * @return The deserialized GameInfo, with transients set.
     */
    @JvmStatic fun loadGameFromFile(filePath: String): GameInfo {
        check(initialized) { "Call initOrThrow(assetsDir) before loading" }
        val gameData = java.io.File(filePath).readText(Charsets.UTF_8)
        return UncivFiles.gameInfoFromString(gameData)
    }

    // ── Apply action (single-dispatch) ────────────────────────────────

    /**
     * Apply a tool-call action to the game.
     *
     * @param gameInfo  The current GameInfo instance.
     * @param actionJson  JSON string matching action schema: {"tool": "...", "args": {...}}
     * @return JSON string: {"success": bool, "error_code"?: str, "error_message"?: str, "state": {...}}
     */
    @JvmStatic fun applyAction(gameInfo: GameInfo, actionJson: String): String {
        val parsed = try {
            JSONObject(actionJson)
        } catch (e: Exception) {
            return buildResult(ActionResult.Error("INVALID_JSON", "Cannot parse: ${e.message}"), gameInfo)
        }

        val tool = parsed.optString("tool", "")
        val args = parsed.optJSONObject("args") ?: JSONObject()

        val result: ActionResult = try {
            when (tool) {
                "end_turn" -> applyEndTurn(gameInfo)
                "choose_tech" -> applyChooseTech(gameInfo, args)
                "choose_production" -> applyChooseProduction(gameInfo, args)
                "move_unit" -> applyMoveUnit(gameInfo, args)
                "attack" -> applyAttack(gameInfo, args)
                "found_city" -> applyFoundCity(gameInfo, args)
                "choose_policy" -> applyChoosePolicy(gameInfo, args)
                "gold_purchase" -> applyGoldPurchase(gameInfo, args)
                "fortify_unit" -> applyFortifyUnit(gameInfo, args)
                "ranged_attack" -> applyRangedAttack(gameInfo, args)
                "pillage" -> applyPillage(gameInfo, args)
                "promote_unit" -> applyPromoteUnit(gameInfo, args)
                "city_bombard" -> applyCityBombard(gameInfo, args)
                "choose_pantheon" -> applyChoosePantheon(gameInfo, args)
                "faith_purchase" -> applyFaithPurchase(gameInfo, args)
                "build_improvement" -> applyBuildImprovement(gameInfo, args)
                "hurry_research" -> applyHurryResearch(gameInfo, args)
                "hurry_production" -> applyHurryProduction(gameInfo, args)
                "hurry_policy" -> applyHurryPolicy(gameInfo, args)
                "found_religion" -> applyFoundReligion(gameInfo, args)
                "enhance_religion" -> applyEnhanceReligion(gameInfo, args)
                "spread_religion" -> applySpreadReligion(gameInfo, args)
                "remove_heresy" -> applyRemoveHeresy(gameInfo, args)
                else -> ActionResult.Error("UNKNOWN_TOOL", "Unknown tool: '$tool'")
            }
        } catch (e: Exception) {
            ActionResult.Error("ENGINE_ERROR", "Engine exception: ${e.message}")
        }

        return buildResult(result, gameInfo)
    }

    private fun buildResult(result: ActionResult, gameInfo: GameInfo): String {
        val json = JSONObject()
        when (result) {
            is ActionResult.Success -> {
                json.put("success", true)
            }
            is ActionResult.Error -> {
                json.put("success", false)
                json.put("error_code", result.code)
                json.put("error_message", result.message)
            }
        }
        json.put("state", JSONObject(extractState(gameInfo)))
        return json.toString()
    }

    // ── Legal action enumeration ──────────────────────────────────────

    /**
     * Enumerate all legal actions the player can take in the current state.
     *
     * @return JSON string: array of {"tool": str, "args": {...}} objects.
     *         Always contains at least [{"tool":"end_turn","args":{}}].
     */
    @JvmStatic fun getLegalActions(gameInfo: GameInfo): String {
        val actions = JSONArray()
        val playerCiv = gameInfo.civilizations.firstOrNull {
            it.playerType == PlayerType.Human
        }
        if (playerCiv == null) {
            actions.put(endTurnAction())
            return actions.toString()
        }

        try { enumerateTechActions(playerCiv, actions) } catch (e: Throwable) {
            System.err.println("getLegalActions: tech enumeration failed: ${e.message}")
        }
        try { enumeratePolicyActions(playerCiv, actions) } catch (e: Throwable) {
            System.err.println("getLegalActions: policy enumeration failed: ${e.message}")
        }
        try { enumerateProductionActions(playerCiv, actions) } catch (e: Throwable) {
            System.err.println("getLegalActions: production enumeration failed: ${e.message}")
        }
        try { enumerateGoldPurchaseActions(playerCiv, actions) } catch (e: Throwable) {
            System.err.println("getLegalActions: gold purchase enumeration failed: ${e.message}")
        }
        try { enumerateUnitActions(gameInfo, playerCiv, actions) } catch (e: Throwable) {
            System.err.println("getLegalActions: unit action enumeration failed: ${e.message}")
        }
        try { enumerateCityBombardActions(gameInfo, playerCiv, actions) } catch (e: Throwable) {
            System.err.println("getLegalActions: city bombard enumeration failed: ${e.message}")
        }
        try { enumeratePantheonActions(playerCiv, actions) } catch (e: Throwable) {
            System.err.println("getLegalActions: pantheon enumeration failed: ${e.message}")
        }
        try { enumerateFaithPurchaseActions(playerCiv, actions) } catch (e: Throwable) {
            System.err.println("getLegalActions: faith purchase enumeration failed: ${e.message}")
        }

        // end_turn is always legal
        actions.put(endTurnAction())
        return actions.toString()
    }

    private fun endTurnAction(): JSONObject {
        val a = JSONObject()
        a.put("tool", "end_turn")
        a.put("args", JSONObject())
        return a
    }

    private fun enumerateTechActions(
        playerCiv: com.unciv.logic.civilization.Civilization,
        actions: JSONArray
    ) {
        val tm = playerCiv.tech
        val ruleset = playerCiv.gameInfo.ruleset
        for (techName in ruleset.technologies.keys.sorted()) {
            if (!tm.isResearched(techName) && tm.canBeResearched(techName)) {
                val a = JSONObject()
                a.put("tool", "choose_tech")
                val args = JSONObject()
                args.put("tech_id", techName)
                a.put("args", args)
                actions.put(a)
            }
        }
    }

    private fun enumerateProductionActions(
        playerCiv: com.unciv.logic.civilization.Civilization,
        actions: JSONArray
    ) {
        val ruleset = playerCiv.gameInfo.ruleset
        for (city in playerCiv.cities.sortedWith(compareBy({ it.civ.civName }, { it.name }))) {
            val cityId = buildCityId(city)
            val cc = city.cityConstructions

            // Buildings (use isBuildable directly — getBuildableBuildings is internal)
            for (building in ruleset.buildings.values) {
                if (building.isBuildable(cc)) {
                    val a = JSONObject()
                    a.put("tool", "choose_production")
                    val args = JSONObject()
                    args.put("city_id", cityId)
                    args.put("build_id", building.name)
                    a.put("args", args)
                    actions.put(a)
                }
            }

            // Units
            for (unit in ruleset.units.values) {
                if (unit.isBuildable(cc)) {
                    val a = JSONObject()
                    a.put("tool", "choose_production")
                    val args = JSONObject()
                    args.put("city_id", cityId)
                    args.put("build_id", unit.name)
                    a.put("args", args)
                    actions.put(a)
                }
            }
        }
    }

    private fun enumerateUnitActions(
        gameInfo: GameInfo,
        playerCiv: com.unciv.logic.civilization.Civilization,
        actions: JSONArray
    ) {
        val unitIds = buildPlayerUnitIds(gameInfo)
        val enemyUnitIds = buildEnemyUnitIds(gameInfo)

        for ((unit, unitId) in unitIds) {
            if (unit.currentMovement <= 0f) continue

            // --- found_city ---
            if (unit.isCivilian()) {
                val hasFoundAbility = try {
                    unit.baseUnit.getMatchingUniques(
                        com.unciv.models.ruleset.unique.UniqueType.FoundCity
                    ).any() || unit.getMatchingUniques(
                        com.unciv.models.ruleset.unique.UniqueType.FoundCity
                    ).any()
                } catch (_: Throwable) {
                    unit.name.contains("Settler", ignoreCase = true)
                }
                if (hasFoundAbility && unit.currentTile.canBeSettled(playerCiv)) {
                    val a = JSONObject()
                    a.put("tool", "found_city")
                    val args = JSONObject()
                    args.put("unit_id", unitId)
                    a.put("args", args)
                    actions.put(a)
                }
            }

            // --- move_unit ---
            val reachable = try {
                unit.movement.getReachableTilesInCurrentTurn().toList()
            } catch (_: Throwable) { emptyList() }

            val currentX = unit.currentTile.position.x
            val currentY = unit.currentTile.position.y
            for (tile in reachable) {
                val tx = tile.position.x
                val ty = tile.position.y
                if (tx == currentX && ty == currentY) continue
                val canEndThere = try { unit.movement.canMoveTo(tile) } catch (_: Throwable) { false }
                if (!canEndThere) continue
                val a = JSONObject()
                a.put("tool", "move_unit")
                val args = JSONObject()
                args.put("unit_id", unitId)
                args.put("target", JSONArray(listOf(tx, ty)))
                a.put("args", args)
                actions.put(a)
            }

            // --- attack / ranged_attack ---
            if (!unit.isCivilian()) {
                val isRanged = unit.baseUnit.rangedStrength > 0
                val toolName = if (isRanged) "ranged_attack" else "attack"
                try {
                    val distToTiles = unit.movement.getDistanceToTiles()
                    val attackable = TargetHelper.getAttackableEnemies(
                        unit, distToTiles
                    )
                    for (att in attackable) {
                        val targetTile = att.tileToAttack
                        val targetId = resolveAttackTargetId(
                            targetTile, playerCiv, enemyUnitIds
                        )
                        if (targetId != null) {
                            val a = JSONObject()
                            a.put("tool", toolName)
                            val args = JSONObject()
                            args.put("unit_id", unitId)
                            args.put("target_id", targetId)
                            a.put("args", args)
                            actions.put(a)
                        }
                    }
                } catch (_: Throwable) {}

                // --- fortify_unit ---
                if (!unit.isFortified()) {
                    val a = JSONObject()
                    a.put("tool", "fortify_unit")
                    val args = JSONObject()
                    args.put("unit_id", unitId)
                    a.put("args", args)
                    actions.put(a)
                }

                // --- pillage ---
                try {
                    val tile = unit.currentTile
                    val hasPillageable = (tile.improvement != null && !tile.improvementIsPillaged) ||
                        (tile.roadStatus.name != "None" && !tile.roadIsPillaged)
                    if (hasPillageable && tile.getOwner() != playerCiv) {
                        val a = JSONObject()
                        a.put("tool", "pillage")
                        val args = JSONObject()
                        args.put("unit_id", unitId)
                        a.put("args", args)
                        actions.put(a)
                    }
                } catch (_: Throwable) {}

                // --- promote_unit ---
                try {
                    val availablePromotions = unit.promotions.getAvailablePromotions().toList()
                    for (promo in availablePromotions.sortedBy { it.name }) {
                        val a = JSONObject()
                        a.put("tool", "promote_unit")
                        val args = JSONObject()
                        args.put("unit_id", unitId)
                        args.put("promotion_id", promo.name)
                        a.put("args", args)
                        actions.put(a)
                    }
                } catch (_: Throwable) {}
            }

            // --- Great Person + Religion unit actions (v0.8.0) ---
            // These apply to civilian GP units, so must be outside the !isCivilian() block

            // --- build_improvement ---
            try {
                val improvUnique = unit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.ConstructImprovementInstantly
                ).firstOrNull() ?: unit.baseUnit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.ConstructImprovementInstantly
                ).firstOrNull()
                if (improvUnique != null) {
                    val improvementFilter = improvUnique.params.firstOrNull() ?: ""
                    val improvement = gameInfo.ruleset.tileImprovements.values.firstOrNull {
                        it.matchesFilter(improvementFilter)
                    }
                    val canBuild = improvement != null && unit.currentTile.improvementFunctions
                        .canBuildImprovement(improvement, unit.cache.state)
                    if (canBuild) {
                        val a = JSONObject()
                        a.put("tool", "build_improvement")
                        val args = JSONObject()
                        args.put("unit_id", unitId)
                        a.put("args", args)
                        actions.put(a)
                    }
                }
            } catch (_: Throwable) {}

            // --- hurry_research ---
            try {
                val hasHurryResearch = unit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.CanHurryResearch
                ).any() || unit.baseUnit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.CanHurryResearch
                ).any()
                if (hasHurryResearch && playerCiv.tech.currentTechnologyName() != null) {
                    val a = JSONObject()
                    a.put("tool", "hurry_research")
                    val args = JSONObject()
                    args.put("unit_id", unitId)
                    a.put("args", args)
                    actions.put(a)
                }
            } catch (_: Throwable) {}

            // --- hurry_production ---
            try {
                val hasHurryProd = unit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.CanSpeedupConstruction
                ).any() || unit.baseUnit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.CanSpeedupConstruction
                ).any() || unit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.CanSpeedupWonderConstruction
                ).any() || unit.baseUnit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.CanSpeedupWonderConstruction
                ).any()
                if (hasHurryProd && unit.currentTile.isCityCenter()) {
                    val city = unit.currentTile.getCity()
                    if (city != null) {
                        val canHurry = try { city.cityConstructions.canBeHurried() } catch (_: Throwable) { false }
                        if (canHurry) {
                            val a = JSONObject()
                            a.put("tool", "hurry_production")
                            val args = JSONObject()
                            args.put("unit_id", unitId)
                            a.put("args", args)
                            actions.put(a)
                        }
                    }
                }
            } catch (_: Throwable) {}

            // --- hurry_policy ---
            try {
                val hasHurryPolicy = unit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.CanHurryPolicy
                ).any() || unit.baseUnit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.CanHurryPolicy
                ).any()
                if (hasHurryPolicy) {
                    val a = JSONObject()
                    a.put("tool", "hurry_policy")
                    val args = JSONObject()
                    args.put("unit_id", unitId)
                    a.put("args", args)
                    actions.put(a)
                }
            } catch (_: Throwable) {}

            // --- found_religion ---
            try {
                val hasFoundRel = unit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.MayFoundReligion
                ).any() || unit.baseUnit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.MayFoundReligion
                ).any()
                if (hasFoundRel) {
                    val rm = playerCiv.religionManager
                    val canFound = try { rm.mayFoundReligionAtAll() } catch (_: Throwable) { false }
                    val canFoundHere = try { rm.mayFoundReligionHere(unit.currentTile) } catch (_: Throwable) { false }
                    if (canFound && canFoundHere) {
                        val availableReligions = gameInfo.ruleset.religions
                            .filter { it !in gameInfo.religions.keys }
                        val defaultName = availableReligions.firstOrNull()
                        val defaultBeliefs = if (defaultName != null)
                            pickDefaultBeliefs(gameInfo, rm.getBeliefsToChooseAtFounding()) else null
                        if (defaultName != null && defaultBeliefs != null) {
                            val a = JSONObject()
                            a.put("tool", "found_religion")
                            val args = JSONObject()
                            args.put("unit_id", unitId)
                            args.put("religion_name", defaultName)
                            val beliefsArr = JSONArray()
                            for (b in defaultBeliefs) beliefsArr.put(b)
                            args.put("beliefs", beliefsArr)
                            a.put("args", args)
                            actions.put(a)
                        }
                    }
                }
            } catch (_: Throwable) {}

            // --- enhance_religion ---
            try {
                val hasEnhanceRel = unit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.MayEnhanceReligion
                ).any() || unit.baseUnit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.MayEnhanceReligion
                ).any()
                if (hasEnhanceRel) {
                    val rm = playerCiv.religionManager
                    val canEnhance = try { rm.mayEnhanceReligionAtAll() } catch (_: Throwable) { false }
                    val canEnhanceHere = try { rm.mayEnhanceReligionHere(unit.currentTile) } catch (_: Throwable) { false }
                    if (canEnhance && canEnhanceHere) {
                        val defaultBeliefs = pickDefaultBeliefs(gameInfo, rm.getBeliefsToChooseAtEnhancing())
                        if (defaultBeliefs != null) {
                            val a = JSONObject()
                            a.put("tool", "enhance_religion")
                            val args = JSONObject()
                            args.put("unit_id", unitId)
                            val beliefsArr = JSONArray()
                            for (b in defaultBeliefs) beliefsArr.put(b)
                            args.put("beliefs", beliefsArr)
                            a.put("args", args)
                            actions.put(a)
                        }
                    }
                }
            } catch (_: Throwable) {}

            // --- spread_religion ---
            try {
                val hasSpread = unit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.CanSpreadReligion
                ).any() || unit.baseUnit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.CanSpreadReligion
                ).any()
                if (hasSpread && unit.religion != null) {
                    val canSpread = try { playerCiv.religionManager.maySpreadReligionNow(unit) } catch (_: Throwable) { false }
                    if (canSpread) {
                        val a = JSONObject()
                        a.put("tool", "spread_religion")
                        val args = JSONObject()
                        args.put("unit_id", unitId)
                        a.put("args", args)
                        actions.put(a)
                    }
                }
            } catch (_: Throwable) {}

            // --- remove_heresy ---
            try {
                val hasRemoveHeresy = unit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.CanRemoveHeresy
                ).any() || unit.baseUnit.getMatchingUniques(
                    com.unciv.models.ruleset.unique.UniqueType.CanRemoveHeresy
                ).any()
                if (hasRemoveHeresy && unit.religion != null) {
                    val tile = unit.currentTile
                    val city = tile.getCity()
                    if (city != null && city.civ == unit.civ) {
                        // Check city has other religions
                        val hasOtherPressure = try {
                            city.religion.getPressures().any { it.key != unit.religion && it.key != "None" && it.value > 0 }
                        } catch (_: Throwable) { false }
                        if (hasOtherPressure) {
                            val a = JSONObject()
                            a.put("tool", "remove_heresy")
                            val args = JSONObject()
                            args.put("unit_id", unitId)
                            a.put("args", args)
                            actions.put(a)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun resolveAttackTargetId(
        targetTile: com.unciv.logic.map.tile.Tile,
        playerCiv: com.unciv.logic.civilization.Civilization,
        enemyUnitIds: List<EnemyUnitWithId>
    ): String? {
        val tx = targetTile.position.x
        val ty = targetTile.position.y
        for ((unit, id) in enemyUnitIds) {
            val ux = unit.currentTile.position.x
            val uy = unit.currentTile.position.y
            if (ux == tx && uy == ty && !unit.isCivilian()) return id
        }
        // Use civ-iteration to find enemy cities (consistent with resolveTarget).
        // Avoids stale tile.getCity() references for razed cities.
        for (otherCiv in playerCiv.getKnownCivs()) {
            if (otherCiv == playerCiv) continue
            for (city in otherCiv.cities) {
                val centerTile = city.getCenterTile()
                if (!centerTile.isExplored(playerCiv)) continue
                if (centerTile.position.x == tx && centerTile.position.y == ty) {
                    return buildEnemyCityId(city)
                }
            }
        }
        return null
    }

    // ── Enumeration helpers ──────────────────────────────────────────

    /**
     * Pick one default belief name per required type from the ruleset,
     * excluding beliefs already claimed by any religion.
     * Returns null if not enough beliefs are available.
     */
    private fun pickDefaultBeliefs(
        gameInfo: GameInfo,
        beliefsNeeded: com.unciv.models.Counter<com.unciv.models.ruleset.BeliefType>
    ): List<String>? {
        val takenBeliefs = gameInfo.religions.values
            .flatMap { try { it.getAllBeliefsOrdered().toList() } catch (_: Throwable) { emptyList() } }
            .map { it.name }.toSet()
        val chosen = mutableListOf<String>()
        for (type in com.unciv.models.ruleset.BeliefType.entries) {
            if (type == com.unciv.models.ruleset.BeliefType.None) continue
            val count = beliefsNeeded[type]
            if (count <= 0) continue
            val available = gameInfo.ruleset.beliefs.values.filter { b ->
                (b.type == type || type == com.unciv.models.ruleset.BeliefType.Any) &&
                b.type != com.unciv.models.ruleset.BeliefType.None &&
                b.name !in takenBeliefs && b.name !in chosen
            }
            if (available.size < count) return null
            chosen.addAll(available.take(count).map { it.name })
        }
        return if (chosen.isEmpty()) null else chosen
    }

    // ── Action helpers ────────────────────────────────────────────────

    private fun applyEndTurn(gameInfo: GameInfo): ActionResult {
        gameInfo.nextTurn()
        System.gc()  // Reclaim AI automation temporaries (pathfinding, eval scores)
        return ActionResult.Success()
    }

    private fun applyChooseTech(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val techId = args.optString("tech_id", "")
        if (techId.isEmpty()) {
            return ActionResult.Error("MISSING_ARGS", "choose_tech requires 'tech_id'")
        }
        val playerCiv = gameInfo.civilizations.firstOrNull {
            it.playerType == PlayerType.Human
        } ?: return ActionResult.Error("ENGINE_ERROR", "No human player found")

        val tm = playerCiv.tech
        val canResearch = try { tm.canBeResearched(techId) } catch (_: Throwable) { false }
        if (!canResearch) {
            return ActionResult.Error("ILLEGAL_ID", "Tech '$techId' cannot be researched")
        }
        tm.techsToResearch.clear()
        tm.techsToResearch.add(techId)
        return ActionResult.Success()
    }

    private fun applyChooseProduction(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val cityId = args.optString("city_id", "")
        if (cityId.isEmpty()) {
            return ActionResult.Error("MISSING_ARGS", "choose_production requires 'city_id'")
        }
        val buildId = args.optString("build_id", "")
        if (buildId.isEmpty()) {
            return ActionResult.Error("MISSING_ARGS", "choose_production requires 'build_id'")
        }
        val city = resolvePlayerCity(gameInfo, cityId)
            ?: return ActionResult.Error("ILLEGAL_ID", "City '$cityId' not found")

        // Validate build_id is a recognized construction
        val isValid = try {
            city.cityConstructions.getConstruction(buildId)
            true
        } catch (_: Throwable) { false }
        if (!isValid) {
            return ActionResult.Error("ILLEGAL_ID", "Build '$buildId' is not a valid construction for city '$cityId'")
        }

        city.cityConstructions.setCurrentConstruction(buildId)
        return ActionResult.Success()
    }

    private fun applyMoveUnit(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        if (unitId.isEmpty()) {
            return ActionResult.Error("MISSING_ARGS", "move_unit requires 'unit_id'")
        }
        val targetArr = args.optJSONArray("target")
        if (targetArr == null || targetArr.length() != 2) {
            return ActionResult.Error("MISSING_ARGS", "move_unit requires 'target' as [x, y]")
        }
        val tx = targetArr.getInt(0)
        val ty = targetArr.getInt(1)

        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")

        val destTile = try {
            gameInfo.tileMap[tx, ty]
        } catch (e: Exception) {
            return ActionResult.Error("ILLEGAL_ID", "Tile [$tx,$ty] out of bounds")
        }

        unit.movement.moveToTile(destTile)
        // Partial moves are treated as success because game state changed
        // (the unit position updated). The agent will observe the new position
        // in the next observation and can decide further movement.
        return ActionResult.Success()
    }

    private fun applyAttack(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        if (unitId.isEmpty()) {
            return ActionResult.Error("MISSING_ARGS", "attack requires 'unit_id'")
        }
        val targetId = args.optString("target_id", "")
        if (targetId.isEmpty()) {
            return ActionResult.Error("MISSING_ARGS", "attack requires 'target_id'")
        }

        val attackerUnit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Attacker unit '$unitId' not found")
        val defender = resolveTarget(gameInfo, targetId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Target '$targetId' not found")

        val attacker = MapUnitCombatant(attackerUnit)
        Battle.attack(attacker, defender)
        return ActionResult.Success()
    }

    private fun applyFoundCity(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        if (unitId.isEmpty()) {
            return ActionResult.Error("MISSING_ARGS", "found_city requires 'unit_id'")
        }

        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")

        if (!unit.isCivilian()) {
            return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' is not a civilian (cannot found city)")
        }
        // Check for settler-type unit (must have city-founding capability)
        val hasFoundAbility = try {
            unit.baseUnit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.FoundCity).any()
                || unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.FoundCity).any()
        } catch (_: Throwable) {
            // Fallback: check unit name for vanilla ruleset
            unit.name.contains("Settler", ignoreCase = true)
        }
        if (!hasFoundAbility) {
            return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' cannot found a city")
        }

        val playerCiv = unit.civ
        val tile = unit.currentTile
        if (!tile.canBeSettled(playerCiv)) {
            return ActionResult.Error("ILLEGAL_MOVE", "Tile [${tile.position.x},${tile.position.y}] cannot be settled")
        }
        playerCiv.addCity(tile.position)
        unit.destroy()
        return ActionResult.Success()
    }

    // ── New apply* methods (v0.6.0) ──────────────────────────────────

    private fun applyChoosePolicy(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val policyId = args.optString("policy_id", "")
        if (policyId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "choose_policy requires 'policy_id'")
        val playerCiv = gameInfo.civilizations.firstOrNull { it.playerType == PlayerType.Human }
            ?: return ActionResult.Error("ENGINE_ERROR", "No human player found")
        val canAdopt = try { playerCiv.policies.canAdoptPolicy() } catch (_: Throwable) { false }
        if (!canAdopt) return ActionResult.Error("ILLEGAL_PURCHASE", "Cannot adopt policy (not enough culture)")
        val policy = gameInfo.ruleset.policies[policyId]
            ?: return ActionResult.Error("ILLEGAL_ID", "Policy '$policyId' not found")
        val isAdoptable = try { playerCiv.policies.isAdoptable(policy) } catch (_: Throwable) { false }
        if (!isAdoptable) return ActionResult.Error("ILLEGAL_ID", "Policy '$policyId' is not adoptable")
        playerCiv.policies.adopt(policy)
        return ActionResult.Success()
    }

    private fun applyGoldPurchase(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val cityId = args.optString("city_id", "")
        val buildId = args.optString("build_id", "")
        if (cityId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "gold_purchase requires 'city_id'")
        if (buildId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "gold_purchase requires 'build_id'")
        val city = resolvePlayerCity(gameInfo, cityId)
            ?: return ActionResult.Error("ILLEGAL_ID", "City '$cityId' not found")
        val playerCiv = city.civ
        val construction = try { city.cityConstructions.getConstruction(buildId) } catch (_: Throwable) { null }
            ?: return ActionResult.Error("ILLEGAL_ID", "Build '$buildId' not found")
        val nonPerpetual = construction as? INonPerpetualConstruction
            ?: return ActionResult.Error("ILLEGAL_PURCHASE", "Build '$buildId' cannot be purchased")
        val cost = try { nonPerpetual.getStatBuyCost(city, Stat.Gold) ?: Int.MAX_VALUE } catch (_: Throwable) { Int.MAX_VALUE }
        if (playerCiv.gold < cost) return ActionResult.Error("ILLEGAL_PURCHASE", "Not enough gold (${playerCiv.gold} < $cost)")
        try {
            city.cityConstructions.purchaseConstruction(buildId, -1, false, Stat.Gold)
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Purchase failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    private fun applyFortifyUnit(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        if (unitId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "fortify_unit requires 'unit_id'")
        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")
        if (unit.isCivilian()) return ActionResult.Error("ILLEGAL_FORTIFY", "Civilian units cannot fortify")
        if (unit.currentMovement <= 0f) return ActionResult.Error("ILLEGAL_FORTIFY", "Unit has no movement")
        try { unit.fortify() } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Fortify failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    private fun applyRangedAttack(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        val targetId = args.optString("target_id", "")
        if (unitId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "ranged_attack requires 'unit_id'")
        if (targetId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "ranged_attack requires 'target_id'")
        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")
        if (unit.baseUnit.rangedStrength <= 0)
            return ActionResult.Error("ILLEGAL_RANGED", "Unit '$unitId' is not a ranged unit")
        val defender = resolveTarget(gameInfo, targetId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Target '$targetId' not found")
        val attacker = MapUnitCombatant(unit)
        Battle.attack(attacker, defender)
        return ActionResult.Success()
    }

    private fun applyPillage(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        if (unitId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "pillage requires 'unit_id'")
        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")
        if (unit.isCivilian()) return ActionResult.Error("ILLEGAL_PILLAGE", "Civilian units cannot pillage")
        val tile = unit.currentTile
        if (tile.improvement == null && tile.roadStatus.name == "None")
            return ActionResult.Error("ILLEGAL_PILLAGE", "Nothing to pillage on tile")
        try {
            tile.setPillaged()
            unit.currentMovement = 0f // pillaging costs all movement
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Pillage failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    private fun applyPromoteUnit(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        val promotionId = args.optString("promotion_id", "")
        if (unitId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "promote_unit requires 'unit_id'")
        if (promotionId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "promote_unit requires 'promotion_id'")
        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")
        val available: List<String> = try {
            unit.promotions.getAvailablePromotions().map { it.name }.toList()
        } catch (_: Throwable) { emptyList<String>() }
        if (!available.contains(promotionId))
            return ActionResult.Error("ILLEGAL_PROMOTE", "Promotion '$promotionId' not available for unit '$unitId'")
        try {
            unit.promotions.addPromotion(promotionId)
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Promote failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    private fun applyCityBombard(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val cityId = args.optString("city_id", "")
        val targetId = args.optString("target_id", "")
        if (cityId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "city_bombard requires 'city_id'")
        if (targetId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "city_bombard requires 'target_id'")
        val city = resolvePlayerCity(gameInfo, cityId)
            ?: return ActionResult.Error("ILLEGAL_ID", "City '$cityId' not found")
        val defender = resolveTarget(gameInfo, targetId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Target '$targetId' not found")
        val attacker = CityCombatant(city)
        try {
            Battle.attack(attacker, defender)
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "City bombard failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    // ── New apply* methods (v0.8.0 — GP + Religion) ────────────────

    private fun applyChoosePantheon(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val beliefId = args.optString("belief_id", "")
        if (beliefId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "choose_pantheon requires 'belief_id'")
        val playerCiv = gameInfo.civilizations.firstOrNull { it.playerType == PlayerType.Human }
            ?: return ActionResult.Error("ENGINE_ERROR", "No human player found")
        val rm = playerCiv.religionManager
        val canPantheon = try { rm.canFoundOrExpandPantheon() } catch (_: Throwable) { false }
        if (!canPantheon) return ActionResult.Error("ILLEGAL_PANTHEON", "Cannot found or expand pantheon")
        val belief = gameInfo.ruleset.beliefs[beliefId]
            ?: return ActionResult.Error("ILLEGAL_ID", "Belief '$beliefId' not found in ruleset")
        if (belief.type != BeliefType.Pantheon)
            return ActionResult.Error("ILLEGAL_PANTHEON", "Belief '$beliefId' is not a Pantheon belief")
        // Check belief is not already taken by another religion
        val taken = gameInfo.religions.values.any { it.hasBelief(beliefId) }
        if (taken) return ActionResult.Error("ILLEGAL_PANTHEON", "Belief '$beliefId' is already taken")
        try {
            rm.chooseBeliefs(listOf(belief))
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Pantheon failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    private fun applyFaithPurchase(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val cityId = args.optString("city_id", "")
        val buildId = args.optString("build_id", "")
        if (cityId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "faith_purchase requires 'city_id'")
        if (buildId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "faith_purchase requires 'build_id'")
        val city = resolvePlayerCity(gameInfo, cityId)
            ?: return ActionResult.Error("ILLEGAL_ID", "City '$cityId' not found")
        val playerCiv = city.civ
        val construction = try { city.cityConstructions.getConstruction(buildId) } catch (_: Throwable) { null }
            ?: return ActionResult.Error("ILLEGAL_ID", "Build '$buildId' not found")
        val nonPerpetual = construction as? INonPerpetualConstruction
            ?: return ActionResult.Error("ILLEGAL_FAITH_PURCHASE", "Build '$buildId' cannot be purchased")
        val canBuyWithFaith = try { nonPerpetual.canBePurchasedWithStat(city, Stat.Faith) } catch (_: Throwable) { false }
        if (!canBuyWithFaith) return ActionResult.Error("ILLEGAL_FAITH_PURCHASE", "Build '$buildId' cannot be purchased with faith")
        val cost = try { nonPerpetual.getStatBuyCost(city, Stat.Faith) ?: Int.MAX_VALUE } catch (_: Throwable) { Int.MAX_VALUE }
        val faith = try { playerCiv.religionManager.storedFaith } catch (_: Throwable) { 0 }
        if (faith < cost) return ActionResult.Error("ILLEGAL_FAITH_PURCHASE", "Not enough faith ($faith < $cost)")
        try {
            city.cityConstructions.purchaseConstruction(buildId, -1, false, Stat.Faith)
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Faith purchase failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    private fun applyBuildImprovement(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        if (unitId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "build_improvement requires 'unit_id'")
        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")
        if (unit.currentMovement <= 0f) return ActionResult.Error("ILLEGAL_GP_ACTION", "Unit has no movement")
        // Find the ConstructImprovementInstantly unique on this unit
        val unique = try {
            unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.ConstructImprovementInstantly).firstOrNull()
                ?: unit.baseUnit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.ConstructImprovementInstantly).firstOrNull()
        } catch (_: Throwable) { null }
            ?: return ActionResult.Error("ILLEGAL_GP_ACTION", "Unit '$unitId' cannot build improvements")
        // Extract improvement name from the unique's filter parameter
        val improvementFilter = unique.params.firstOrNull() ?: ""
        val improvement = gameInfo.ruleset.tileImprovements.values.firstOrNull {
            it.matchesFilter(improvementFilter)
        } ?: return ActionResult.Error("ILLEGAL_GP_ACTION", "No matching improvement for filter '$improvementFilter'")
        val tile = unit.currentTile
        val canBuild = try {
            tile.improvementFunctions.canBuildImprovement(improvement, unit.cache.state)
        } catch (_: Throwable) { false }
        if (!canBuild) return ActionResult.Error("ILLEGAL_GP_ACTION", "Cannot build '${improvement.name}' on current tile")
        try {
            tile.setImprovement(improvement.name, unit.civ, unit)
            unit.consume()
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Build improvement failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    private fun applyHurryResearch(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        if (unitId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "hurry_research requires 'unit_id'")
        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")
        if (unit.currentMovement <= 0f) return ActionResult.Error("ILLEGAL_GP_ACTION", "Unit has no movement")
        val hasAbility = try {
            unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanHurryResearch).any()
                || unit.baseUnit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanHurryResearch).any()
        } catch (_: Throwable) { false }
        if (!hasAbility) return ActionResult.Error("ILLEGAL_GP_ACTION", "Unit '$unitId' cannot hurry research")
        val tm = unit.civ.tech
        if (tm.currentTechnologyName() == null)
            return ActionResult.Error("ILLEGAL_GP_ACTION", "No technology currently being researched")
        try {
            tm.addScience(tm.getScienceFromGreatScientist())
            unit.consume()
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Hurry research failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    private fun applyHurryProduction(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        if (unitId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "hurry_production requires 'unit_id'")
        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")
        if (unit.currentMovement <= 0f) return ActionResult.Error("ILLEGAL_GP_ACTION", "Unit has no movement")
        val hasAbility = try {
            unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanSpeedupConstruction).any()
                || unit.baseUnit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanSpeedupConstruction).any()
                || unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanSpeedupWonderConstruction).any()
                || unit.baseUnit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanSpeedupWonderConstruction).any()
        } catch (_: Throwable) { false }
        if (!hasAbility) return ActionResult.Error("ILLEGAL_GP_ACTION", "Unit '$unitId' cannot hurry production")
        val tile = unit.currentTile
        if (!tile.isCityCenter())
            return ActionResult.Error("ILLEGAL_GP_ACTION", "Unit must be on a city center to hurry production")
        val city = tile.getCity()
            ?: return ActionResult.Error("ILLEGAL_GP_ACTION", "No city on current tile")
        val cc = city.cityConstructions
        val canHurry = try { cc.canBeHurried() } catch (_: Throwable) { false }
        if (!canHurry) return ActionResult.Error("ILLEGAL_GP_ACTION", "Current construction cannot be hurried")
        try {
            val remaining = cc.getRemainingWork(cc.currentConstructionName()).toFloat()
            val productionPoints = minOf(
                (300 + 30 * city.population.population) * gameInfo.speed.productionCostModifier,
                remaining - 1
            ).toInt()
            if (productionPoints > 0) {
                cc.addProductionPoints(productionPoints)
                cc.constructIfEnough()
            }
            unit.consume()
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Hurry production failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    private fun applyHurryPolicy(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        if (unitId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "hurry_policy requires 'unit_id'")
        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")
        if (unit.currentMovement <= 0f) return ActionResult.Error("ILLEGAL_GP_ACTION", "Unit has no movement")
        val hasAbility = try {
            unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanHurryPolicy).any()
                || unit.baseUnit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanHurryPolicy).any()
        } catch (_: Throwable) { false }
        if (!hasAbility) return ActionResult.Error("ILLEGAL_GP_ACTION", "Unit '$unitId' cannot hurry policy")
        try {
            unit.civ.policies.addCulture(unit.civ.policies.getCultureFromGreatWriter())
            unit.consume()
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Hurry policy failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    private fun applyFoundReligion(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        if (unitId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "found_religion requires 'unit_id'")
        val religionName = args.optString("religion_name", "")
        if (religionName.isEmpty()) return ActionResult.Error("MISSING_ARGS", "found_religion requires 'religion_name'")
        val beliefsArr = args.optJSONArray("beliefs")
        if (beliefsArr == null || beliefsArr.length() == 0)
            return ActionResult.Error("MISSING_ARGS", "found_religion requires 'beliefs' array")
        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")
        if (unit.currentMovement <= 0f) return ActionResult.Error("ILLEGAL_RELIGION", "Unit has no movement")
        val hasAbility = try {
            unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.MayFoundReligion).any()
                || unit.baseUnit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.MayFoundReligion).any()
        } catch (_: Throwable) { false }
        if (!hasAbility) return ActionResult.Error("ILLEGAL_RELIGION", "Unit '$unitId' cannot found a religion")
        val rm = unit.civ.religionManager
        val canFound = try { rm.mayFoundReligionAtAll() } catch (_: Throwable) { false }
        if (!canFound) return ActionResult.Error("ILLEGAL_RELIGION", "Cannot found religion")
        val canFoundHere = try { rm.mayFoundReligionHere(unit.currentTile) } catch (_: Throwable) { false }
        if (!canFoundHere) return ActionResult.Error("ILLEGAL_RELIGION", "Cannot found religion on current tile")
        // Validate religion_name is an available religion icon key
        val availableReligions = gameInfo.ruleset.religions.filter { it !in gameInfo.religions.keys }
        if (religionName !in availableReligions)
            return ActionResult.Error("ILLEGAL_RELIGION", "Religion name '$religionName' is not available")
        // Resolve belief objects
        val beliefNames = (0 until beliefsArr.length()).map { beliefsArr.getString(it) }
        val beliefs = mutableListOf<com.unciv.models.ruleset.Belief>()
        for (name in beliefNames) {
            val belief = gameInfo.ruleset.beliefs[name]
                ?: return ActionResult.Error("ILLEGAL_ID", "Belief '$name' not found in ruleset")
            beliefs.add(belief)
        }
        try {
            // Step 1: Use prophet — sets state to FoundingReligion
            rm.foundReligion(unit)
            // Step 2: Create the religion with name
            rm.foundReligion(religionName, religionName)
            // Step 3: Add beliefs and transition to Religion state
            rm.chooseBeliefs(beliefs)
            // Step 4: Consume the prophet unit
            unit.consume()
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Found religion failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    private fun applyEnhanceReligion(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        if (unitId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "enhance_religion requires 'unit_id'")
        val beliefsArr = args.optJSONArray("beliefs")
        if (beliefsArr == null || beliefsArr.length() == 0)
            return ActionResult.Error("MISSING_ARGS", "enhance_religion requires 'beliefs' array")
        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")
        if (unit.currentMovement <= 0f) return ActionResult.Error("ILLEGAL_RELIGION", "Unit has no movement")
        val hasAbility = try {
            unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.MayEnhanceReligion).any()
                || unit.baseUnit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.MayEnhanceReligion).any()
        } catch (_: Throwable) { false }
        if (!hasAbility) return ActionResult.Error("ILLEGAL_RELIGION", "Unit '$unitId' cannot enhance religion")
        val rm = unit.civ.religionManager
        val canEnhance = try { rm.mayEnhanceReligionAtAll() } catch (_: Throwable) { false }
        if (!canEnhance) return ActionResult.Error("ILLEGAL_RELIGION", "Cannot enhance religion")
        val canEnhanceHere = try { rm.mayEnhanceReligionHere(unit.currentTile) } catch (_: Throwable) { false }
        if (!canEnhanceHere) return ActionResult.Error("ILLEGAL_RELIGION", "Cannot enhance religion on current tile")
        // Resolve beliefs
        val beliefNames = (0 until beliefsArr.length()).map { beliefsArr.getString(it) }
        val beliefs = mutableListOf<com.unciv.models.ruleset.Belief>()
        for (name in beliefNames) {
            val belief = gameInfo.ruleset.beliefs[name]
                ?: return ActionResult.Error("ILLEGAL_ID", "Belief '$name' not found in ruleset")
            beliefs.add(belief)
        }
        try {
            // Step 1: Use prophet — sets state to EnhancingReligion
            rm.useProphetForEnhancingReligion(unit)
            // Step 2: Add beliefs and transition to EnhancedReligion state
            rm.chooseBeliefs(beliefs)
            // Step 3: Consume the prophet unit
            unit.consume()
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Enhance religion failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    private fun applySpreadReligion(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        if (unitId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "spread_religion requires 'unit_id'")
        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")
        if (unit.currentMovement <= 0f) return ActionResult.Error("ILLEGAL_RELIGION", "Unit has no movement")
        val hasAbility = try {
            unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanSpreadReligion).any()
                || unit.baseUnit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanSpreadReligion).any()
        } catch (_: Throwable) { false }
        if (!hasAbility) return ActionResult.Error("ILLEGAL_RELIGION", "Unit '$unitId' cannot spread religion")
        val unitReligion = unit.religion
        if (unitReligion == null) return ActionResult.Error("ILLEGAL_RELIGION", "Unit has no religion to spread")
        val rm = unit.civ.religionManager
        val canSpread = try { rm.maySpreadReligionNow(unit) } catch (_: Throwable) { false }
        if (!canSpread) return ActionResult.Error("ILLEGAL_RELIGION", "Cannot spread religion here")
        val tile = unit.currentTile
        val city = tile.getCity()
            ?: return ActionResult.Error("ILLEGAL_RELIGION", "No city on current tile")
        try {
            // Calculate pressure to add (base religiousStrength * modifiers)
            var pressure = unit.baseUnit.religiousStrength.toFloat()
            for (unique in unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.SpreadReligionStrength, checkCivInfoUniques = true)) {
                pressure *= unique.params[0].toFloat() / 100f
            }
            city.religion.addPressure(unitReligion, pressure.toInt())
            // Consume movement and handle charges
            unit.currentMovement = 0f
            // Decrement charges via abilityToTimesUsed
            val spreadUnique = unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanSpreadReligion).firstOrNull()
            if (spreadUnique != null) {
                val key = spreadUnique.text.replace(Regex("\\s*<.*?>\\s*"), "").trim()
                val usages = unit.abilityToTimesUsed.getOrDefault(key, 0)
                unit.abilityToTimesUsed[key] = usages + 1
                // Check if unit should be consumed (all charges used)
                val maxUses = try {
                    spreadUnique.modifiers.firstOrNull {
                        it.type == com.unciv.models.ruleset.unique.UniqueType.UnitActionLimitedTimes
                    }?.params?.get(0)?.toIntOrNull() ?: 1
                } catch (_: Throwable) { 1 }
                if (usages + 1 >= maxUses) unit.consume()
            } else {
                unit.consume()
            }
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Spread religion failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    private fun applyRemoveHeresy(gameInfo: GameInfo, args: JSONObject): ActionResult {
        val unitId = args.optString("unit_id", "")
        if (unitId.isEmpty()) return ActionResult.Error("MISSING_ARGS", "remove_heresy requires 'unit_id'")
        val unit = resolvePlayerUnit(gameInfo, unitId)
            ?: return ActionResult.Error("ILLEGAL_ID", "Unit '$unitId' not found")
        if (unit.currentMovement <= 0f) return ActionResult.Error("ILLEGAL_RELIGION", "Unit has no movement")
        val hasAbility = try {
            unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanRemoveHeresy).any()
                || unit.baseUnit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanRemoveHeresy).any()
        } catch (_: Throwable) { false }
        if (!hasAbility) return ActionResult.Error("ILLEGAL_RELIGION", "Unit '$unitId' cannot remove heresy")
        val unitReligion = unit.religion
        if (unitReligion == null) return ActionResult.Error("ILLEGAL_RELIGION", "Unit has no religion")
        val tile = unit.currentTile
        val city = tile.getCity()
            ?: return ActionResult.Error("ILLEGAL_RELIGION", "No city on current tile")
        if (city.civ != unit.civ)
            return ActionResult.Error("ILLEGAL_RELIGION", "Can only remove heresy in own cities")
        try {
            city.religion.removeAllPressuresExceptFor(unitReligion)
            // Handle holy city blocking
            if (city.religion.religionThisIsTheHolyCityOf != null) {
                val holyCityReligionName = city.religion.religionThisIsTheHolyCityOf!!
                if (holyCityReligionName != unitReligion && !city.religion.isBlockedHolyCity) {
                    city.religion.isBlockedHolyCity = true
                } else if (holyCityReligionName == unitReligion && city.religion.isBlockedHolyCity) {
                    city.religion.isBlockedHolyCity = false
                }
            }
            unit.currentMovement = 0f
            // Decrement charges
            val heresyUnique = unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.CanRemoveHeresy).firstOrNull()
            if (heresyUnique != null) {
                val key = heresyUnique.text.replace(Regex("\\s*<.*?>\\s*"), "").trim()
                val usages = unit.abilityToTimesUsed.getOrDefault(key, 0)
                unit.abilityToTimesUsed[key] = usages + 1
                val maxUses = try {
                    heresyUnique.modifiers.firstOrNull {
                        it.type == com.unciv.models.ruleset.unique.UniqueType.UnitActionLimitedTimes
                    }?.params?.get(0)?.toIntOrNull() ?: 1
                } catch (_: Throwable) { 1 }
                if (usages + 1 >= maxUses) unit.consume()
            } else {
                unit.consume()
            }
        } catch (e: Throwable) {
            return ActionResult.Error("ENGINE_ERROR", "Remove heresy failed: ${e.message}")
        }
        return ActionResult.Success()
    }

    // ── New enumerators (v0.6.0) ───────────────────────────────────

    private fun enumeratePolicyActions(
        playerCiv: com.unciv.logic.civilization.Civilization, actions: JSONArray
    ) {
        val canAdopt = try { playerCiv.policies.canAdoptPolicy() } catch (_: Throwable) { false }
        if (!canAdopt) return
        val adoptable = try {
            playerCiv.gameInfo.ruleset.policies.values.filter { playerCiv.policies.isAdoptable(it) }
        } catch (_: Throwable) { emptyList() }
        for (policy in adoptable.sortedBy { it.name }) {
            val a = JSONObject()
            a.put("tool", "choose_policy")
            val args = JSONObject()
            args.put("policy_id", policy.name)
            a.put("args", args)
            actions.put(a)
        }
    }

    private fun enumerateGoldPurchaseActions(
        playerCiv: com.unciv.logic.civilization.Civilization, actions: JSONArray
    ) {
        for (city in playerCiv.cities.sortedWith(compareBy({ it.civ.civName }, { it.name }))) {
            val cityId = buildCityId(city)
            try {
                val constructions = city.cityConstructions
                val ruleset = playerCiv.gameInfo.ruleset
                for (unit in ruleset.units.values) {
                    if (unit.isBuildable(constructions)) {
                        val cost = try { unit.getStatBuyCost(city, Stat.Gold) ?: Int.MAX_VALUE } catch (_: Throwable) { Int.MAX_VALUE }
                        if (playerCiv.gold >= cost) {
                            val a = JSONObject()
                            a.put("tool", "gold_purchase")
                            val args = JSONObject()
                            args.put("city_id", cityId)
                            args.put("build_id", unit.name)
                            a.put("args", args)
                            actions.put(a)
                        }
                    }
                }
                for (building in ruleset.buildings.values) {
                    if (building.isBuildable(constructions)) {
                        val cost = try { building.getStatBuyCost(city, Stat.Gold) ?: Int.MAX_VALUE } catch (_: Throwable) { Int.MAX_VALUE }
                        if (playerCiv.gold >= cost) {
                            val a = JSONObject()
                            a.put("tool", "gold_purchase")
                            val args = JSONObject()
                            args.put("city_id", cityId)
                            args.put("build_id", building.name)
                            a.put("args", args)
                            actions.put(a)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun enumerateCityBombardActions(
        gameInfo: GameInfo,
        playerCiv: com.unciv.logic.civilization.Civilization,
        actions: JSONArray
    ) {
        val enemyUnitIds = buildEnemyUnitIds(gameInfo)
        for (city in playerCiv.cities.sortedWith(compareBy({ it.civ.civName }, { it.name }))) {
            val canAttack = try { !city.attackedThisTurn } catch (_: Throwable) { false }
            if (!canAttack) continue
            val cityId = buildCityId(city)
            val centerTile = city.getCenterTile()
            val bombRange = 2

            // Find enemies in range
            for ((unit, enemyId) in enemyUnitIds) {
                val dist = try {
                    centerTile.aerialDistanceTo(unit.currentTile)
                } catch (_: Throwable) { Int.MAX_VALUE }
                if (dist <= bombRange && !unit.isCivilian()) {
                    val a = JSONObject()
                    a.put("tool", "city_bombard")
                    val args = JSONObject()
                    args.put("city_id", cityId)
                    args.put("target_id", enemyId)
                    a.put("args", args)
                    actions.put(a)
                }
            }
        }
    }

    // ── New enumerators (v0.8.0 — Pantheon + Faith purchase) ───────

    private fun enumeratePantheonActions(
        playerCiv: com.unciv.logic.civilization.Civilization, actions: JSONArray
    ) {
        val rm = playerCiv.religionManager
        val canPantheon = try { rm.canFoundOrExpandPantheon() } catch (_: Throwable) { false }
        if (!canPantheon) return
        val gameInfo = playerCiv.gameInfo
        // Enumerate available pantheon beliefs (not taken by any religion)
        val takenBeliefs = gameInfo.religions.values.flatMap { r ->
            try { r.getAllBeliefsOrdered().map { it.name }.toList() } catch (_: Throwable) { emptyList() }
        }.toSet()
        for (belief in gameInfo.ruleset.beliefs.values.filter {
            it.type == BeliefType.Pantheon && it.name !in takenBeliefs
        }.sortedBy { it.name }) {
            val a = JSONObject()
            a.put("tool", "choose_pantheon")
            val args = JSONObject()
            args.put("belief_id", belief.name)
            a.put("args", args)
            actions.put(a)
        }
    }

    private fun enumerateFaithPurchaseActions(
        playerCiv: com.unciv.logic.civilization.Civilization, actions: JSONArray
    ) {
        val faith = try { playerCiv.religionManager.storedFaith } catch (_: Throwable) { 0 }
        if (faith <= 0) return
        for (city in playerCiv.cities.sortedWith(compareBy({ it.civ.civName }, { it.name }))) {
            val cityId = buildCityId(city)
            try {
                val constructions = city.cityConstructions
                val ruleset = playerCiv.gameInfo.ruleset
                for (unit in ruleset.units.values) {
                    val canBuy = try { unit.canBePurchasedWithStat(city, Stat.Faith) } catch (_: Throwable) { false }
                    if (canBuy) {
                        val cost = try { unit.getStatBuyCost(city, Stat.Faith) ?: Int.MAX_VALUE } catch (_: Throwable) { Int.MAX_VALUE }
                        if (faith >= cost) {
                            val a = JSONObject()
                            a.put("tool", "faith_purchase")
                            val args = JSONObject()
                            args.put("city_id", cityId)
                            args.put("build_id", unit.name)
                            a.put("args", args)
                            actions.put(a)
                        }
                    }
                }
                for (building in ruleset.buildings.values) {
                    val canBuy = try { building.canBePurchasedWithStat(city, Stat.Faith) } catch (_: Throwable) { false }
                    if (canBuy) {
                        val cost = try { building.getStatBuyCost(city, Stat.Faith) ?: Int.MAX_VALUE } catch (_: Throwable) { Int.MAX_VALUE }
                        if (faith >= cost) {
                            val a = JSONObject()
                            a.put("tool", "faith_purchase")
                            val args = JSONObject()
                            args.put("city_id", cityId)
                            args.put("build_id", building.name)
                            a.put("args", args)
                            actions.put(a)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    // ── Resolver helpers ──────────────────────────────────────────────

    private fun resolvePlayerUnit(gameInfo: GameInfo, unitId: String): com.unciv.logic.map.mapunit.MapUnit? {
        val playerCiv = gameInfo.civilizations.firstOrNull {
            it.playerType == PlayerType.Human
        } ?: return null
        val civName = playerCiv.civName
        val unitsList = playerCiv.units.getCivUnits().filter { !it.isDestroyed }.toList().sortedWith(compareBy(
            { it.civ.civName },
            { it.name },
            { it.currentTile.position.x },
            { it.currentTile.position.y }
        ))
        val idCounts = mutableMapOf<String, Int>()
        for (unit in unitsList) {
            val typeName = unit.name
            val x = unit.currentTile.position.x
            val y = unit.currentTile.position.y
            val baseId = "u:${typeName}:${x},${y}:${civName}"
            val idx = idCounts.getOrDefault(baseId, 0)
            idCounts[baseId] = idx + 1
            val id = if (idx == 0) baseId else "${baseId}:${idx}"
            if (id == unitId) return unit
        }
        return null
    }

    private fun resolvePlayerCity(gameInfo: GameInfo, cityId: String): com.unciv.logic.city.City? {
        val playerCiv = gameInfo.civilizations.firstOrNull {
            it.playerType == PlayerType.Human
        } ?: return null
        for (city in playerCiv.cities) {
            val name = city.name
            val x = city.location.x.toInt()
            val y = city.location.y.toInt()
            val id = "c:${name}:${x},${y}"
            if (id == cityId) return city
        }
        return null
    }

    private fun resolveTarget(gameInfo: GameInfo, targetId: String): com.unciv.logic.battle.ICombatant? {
        val playerCiv = gameInfo.civilizations.firstOrNull {
            it.playerType == PlayerType.Human
        } ?: return null

        if (targetId.startsWith("eu:")) {
            val enemyUnits = mutableListOf<Pair<String, com.unciv.logic.map.mapunit.MapUnit>>()
            for (tile in playerCiv.viewableTiles) {
                for (unit in tile.getUnits()) {
                    if (unit.civ == playerCiv) continue
                    if (unit.isInvisible(playerCiv)) continue
                    val typeName = unit.name
                    val ownerName = unit.civ.civName
                    val x = tile.position.x
                    val y = tile.position.y
                    val baseId = "eu:${typeName}:${x},${y}:${ownerName}"
                    enemyUnits.add(baseId to unit)
                }
            }
            val sorted = enemyUnits.sortedWith(compareBy(
                { it.second.civ.civName },
                { it.second.name },
                { it.second.currentTile.position.x },
                { it.second.currentTile.position.y }
            ))
            val idCounts = mutableMapOf<String, Int>()
            for ((baseId, unit) in sorted) {
                val idx = idCounts.getOrDefault(baseId, 0)
                idCounts[baseId] = idx + 1
                val id = if (idx == 0) baseId else "${baseId}:${idx}"
                if (id == targetId) return MapUnitCombatant(unit)
            }
        }

        if (targetId.startsWith("ec:")) {
            for (otherCiv in playerCiv.getKnownCivs()) {
                if (otherCiv == playerCiv) continue
                for (city in otherCiv.cities) {
                    val centerTile = city.getCenterTile()
                    if (!centerTile.isExplored(playerCiv)) continue
                    val name = city.name
                    val x = centerTile.position.x
                    val y = centerTile.position.y
                    val id = "ec:${name}:${x},${y}"
                    if (id == targetId) return CityCombatant(city)
                }
            }
        }

        return null
    }

    private fun extractGlobal(gameInfo: GameInfo, playerCiv: com.unciv.logic.civilization.Civilization?): JSONObject {
        val global = JSONObject()
        global.put("turn", gameInfo.turns)
        global.put("phase", "TECH_SELECTION") // controller owns real phase
        val atWar = if (playerCiv != null) {
            playerCiv.getKnownCivs().any { playerCiv.isAtWarWith(it) }
        } else false
        global.put("war_state", if (atWar) "at_war" else "peace")

        // Economy fields
        if (playerCiv != null) {
            global.put("gold", safeInt { playerCiv.gold })
            global.put("gold_per_turn", safeInt { playerCiv.stats.statsForNextTurn.gold.toInt() })
            global.put("happiness", safeInt { playerCiv.getHappiness() })
            global.put("culture", safeInt { playerCiv.policies.storedCulture })
            global.put("culture_per_turn", safeInt { playerCiv.stats.statsForNextTurn.culture.toInt() })
            global.put("faith", safeInt { try { playerCiv.religionManager.storedFaith } catch (_: Throwable) { 0 } })
            global.put("faith_per_turn", safeInt { playerCiv.stats.statsForNextTurn.faith.toInt() })
            global.put("science_per_turn", safeInt { playerCiv.stats.statsForNextTurn.science.toInt() })
            global.put("era", safeString { playerCiv.getEra().name } ?: "Ancient era")

            // Victory/defeat signals for episode termination
            global.put("is_defeated", try { playerCiv.isDefeated() } catch (_: Throwable) { false })
            global.put("is_player_victory", try { playerCiv.victoryManager.hasWon() } catch (_: Throwable) { false })
        } else {
            global.put("gold", 0); global.put("gold_per_turn", 0)
            global.put("happiness", 0)
            global.put("culture", 0); global.put("culture_per_turn", 0)
            global.put("faith", 0); global.put("faith_per_turn", 0)
            global.put("science_per_turn", 0); global.put("era", "Ancient era")
            global.put("is_defeated", false); global.put("is_player_victory", false)
        }
        return global
    }

    private fun extractCities(playerCiv: com.unciv.logic.civilization.Civilization?): JSONArray {
        val citiesArr = JSONArray()
        if (playerCiv == null) return citiesArr
        val sorted = playerCiv.cities.sortedWith(compareBy(
            { it.civ.civName },
            { it.name }
        ))
        for (city in sorted) {
            val c = JSONObject()
            val name = city.name
            val x = city.location.x.toInt()
            val y = city.location.y.toInt()
            c.put("id", "c:${name}:${x},${y}")
            c.put("name", name)
            c.put("owner", playerCiv.civName)
            c.put("position", JSONArray(listOf(x, y)))
            c.put("population", city.population.population)
            c.put("health", safeInt { city.health })
            c.put("current_production", safeString { city.cityConstructions.currentConstructionName() } ?: "")
            val queue = JSONArray()
            try { for (item in city.cityConstructions.constructionQueue) { queue.put(item) } } catch (_: Throwable) {}
            c.put("production_queue", queue)
            // Yields
            c.put("food", safeInt { city.cityStats.currentCityStats.food.toInt() })
            c.put("food_stored", safeInt { city.population.foodStored })
            c.put("turns_to_grow", safeInt {
                val surplus = city.cityStats.currentCityStats.food.toInt()
                if (surplus <= 0) -1
                else {
                    val needed = city.population.getFoodToNextPopulation() - city.population.foodStored
                    if (needed <= 0) 0 else (needed + surplus - 1) / surplus
                }
            })
            c.put("production_amount", safeInt { city.cityStats.currentCityStats.production.toInt() })
            c.put("gold_yield", safeInt { city.cityStats.currentCityStats.gold.toInt() })
            c.put("science_yield", safeInt { city.cityStats.currentCityStats.science.toInt() })
            c.put("culture_yield", safeInt { city.cityStats.currentCityStats.culture.toInt() })
            c.put("defense", safeInt { city.getMaxHealth() })
            val buildings = JSONArray()
            try { for (b in city.cityConstructions.builtBuildings.sorted()) { buildings.put(b) } } catch (_: Throwable) {}
            c.put("buildings", buildings)
            val canBombard = try { !city.attackedThisTurn && city.cityConstructions.builtBuildings.any() } catch (_: Throwable) { false }
            c.put("can_bombard", canBombard)
            c.put("bombard_range", 2)
            citiesArr.put(c)
        }
        return citiesArr
    }

    private fun extractUnits(playerCiv: com.unciv.logic.civilization.Civilization?): JSONArray {
        val unitsArr = JSONArray()
        if (playerCiv == null) return unitsArr
        val civName = playerCiv.civName
        val unitsList = playerCiv.units.getCivUnits().filter { !it.isDestroyed }.toList().sortedWith(compareBy(
            { it.civ.civName },
            { it.name },
            { it.currentTile.position.x },
            { it.currentTile.position.y }
        ))
        // Track base-id counts for uniqueness suffix
        val idCounts = mutableMapOf<String, Int>()
        for (unit in unitsList) {
            val u = JSONObject()
            val typeName = unit.name
            val x = unit.currentTile.position.x
            val y = unit.currentTile.position.y
            val baseId = "u:${typeName}:${x},${y}:${civName}"
            val idx = idCounts.getOrDefault(baseId, 0)
            idCounts[baseId] = idx + 1
            val uniqueId = if (idx == 0) baseId else "${baseId}:${idx}"
            u.put("id", uniqueId)
            u.put("owner", civName)
            u.put("type", typeName)
            u.put("hp", unit.health)
            u.put("movement", unit.currentMovement.toDouble())
            u.put("position", JSONArray(listOf(x, y)))
            u.put("is_civilian", unit.isCivilian())
            // Combat stats
            u.put("strength", safeInt { unit.baseUnit.strength })
            u.put("ranged_strength", safeInt { unit.baseUnit.rangedStrength })
            u.put("range", safeInt { unit.baseUnit.range })
            val promotions = JSONArray()
            try { for (p in unit.promotions.promotions.sorted()) { promotions.put(p) } } catch (_: Throwable) {}
            u.put("promotions", promotions)
            val availablePromos = JSONArray()
            try {
                for (p in unit.promotions.getAvailablePromotions().map { it.name }.sorted()) {
                    availablePromos.put(p)
                }
            } catch (_: Throwable) {}
            u.put("available_promotions", availablePromos)
            u.put("experience", safeInt { unit.promotions.XP })
            u.put("is_fortified", try { unit.isFortified() } catch (_: Throwable) { false })
            u.put("can_attack", try { unit.canAttack() } catch (_: Throwable) { false })
            unitsArr.put(u)
        }
        return unitsArr
    }

    private fun extractEnemyCities(playerCiv: com.unciv.logic.civilization.Civilization?): JSONArray {
        val arr = JSONArray()
        if (playerCiv == null) return arr
        val enemyCities = mutableListOf<JSONObject>()
        try {
            for (otherCiv in playerCiv.getKnownCivs()) {
                if (otherCiv == playerCiv) continue
                for (city in otherCiv.cities) {
                    val centerTile = city.getCenterTile()
                    if (centerTile.isExplored(playerCiv)) {
                        val c = JSONObject()
                        val name = city.name
                        val x = centerTile.position.x
                        val y = centerTile.position.y
                        c.put("id", "ec:${name}:${x},${y}")
                        c.put("name", name)
                        c.put("owner", otherCiv.civName)
                        c.put("position", JSONArray(listOf(x, y)))
                        c.put("population", city.population.population)
                        c.put("health", safeInt { city.health })
                        c.put("defense", safeInt { city.getMaxHealth() })
                        enemyCities.add(c)
                    }
                }
            }
        } catch (_: Throwable) {}
        for (c in enemyCities.sortedWith(compareBy(
            { it.getString("owner") },
            { it.getString("name") }
        ))) { arr.put(c) }
        return arr
    }

    private fun extractEnemyUnits(playerCiv: com.unciv.logic.civilization.Civilization?): JSONArray {
        val arr = JSONArray()
        if (playerCiv == null) return arr
        val enemyUnits = mutableListOf<Pair<String, JSONObject>>()
        try {
            for (tile in playerCiv.viewableTiles) {
                for (unit in tile.getUnits()) {
                    if (unit.civ == playerCiv) continue
                    if (unit.isInvisible(playerCiv)) continue
                    val u = JSONObject()
                    val typeName = unit.name
                    val ownerName = unit.civ.civName
                    val x = tile.position.x
                    val y = tile.position.y
                    val baseId = "eu:${typeName}:${x},${y}:${ownerName}"
                    u.put("owner", ownerName)
                    u.put("type", typeName)
                    u.put("hp", unit.health)
                    u.put("movement", unit.currentMovement.toDouble())
                    u.put("position", JSONArray(listOf(x, y)))
                    u.put("is_civilian", unit.isCivilian())
                    // Combat stats
                    u.put("strength", safeInt { unit.baseUnit.strength })
                    u.put("ranged_strength", safeInt { unit.baseUnit.rangedStrength })
                    u.put("range", safeInt { unit.baseUnit.range })
                    u.put("is_fortified", try { unit.isFortified() } catch (_: Throwable) { false })
                    enemyUnits.add(baseId to u)
                }
            }
        } catch (_: Throwable) {}
        // Sort canonically by (owner, type, x, y), then assign unique IDs
        val sorted = enemyUnits.sortedWith(compareBy(
            { it.second.getString("owner") },
            { it.second.getString("type") },
            { it.second.getJSONArray("position").getInt(0) },
            { it.second.getJSONArray("position").getInt(1) }
        ))
        val idCounts = mutableMapOf<String, Int>()
        for ((baseId, u) in sorted) {
            val idx = idCounts.getOrDefault(baseId, 0)
            idCounts[baseId] = idx + 1
            val uniqueId = if (idx == 0) baseId else "${baseId}:${idx}"
            u.put("id", uniqueId)
            arr.put(u)
        }
        return arr
    }

    private fun extractMap(gameInfo: GameInfo, playerCiv: com.unciv.logic.civilization.Civilization?): JSONObject {
        val mapObj = JSONObject()
        val tilesArr = JSONArray()
        val tileMap = gameInfo.tileMap
        val sortedTiles = tileMap.values.sortedWith(compareBy({ it.position.x }, { it.position.y }))
        for (tile in sortedTiles) {
            val t = JSONObject()
            t.put("x", tile.position.x)
            t.put("y", tile.position.y)
            t.put("terrain", tile.baseTerrain)
            val features = JSONArray()
            try { for (f in tile.terrainFeatures.sorted()) { features.put(f) } } catch (_: Throwable) {}
            t.put("terrain_features", features)
            val resource = try { tile.resource } catch (_: Throwable) { null }
            t.put("resource", resource ?: JSONObject.NULL)
            t.put("owner", safeString { tile.getOwner()?.civName } ?: "")
            // Improvement fields
            t.put("improvement", safeString { tile.improvement } ?: JSONObject.NULL)
            val roadStatus = try { tile.roadStatus.name } catch (_: Throwable) { "None" }
            t.put("road", if (roadStatus == "None") JSONObject.NULL else roadStatus)
            t.put("pillaged", try { tile.improvementIsPillaged } catch (_: Throwable) { false })
            if (playerCiv != null) {
                t.put("visible", try { tile.isVisible(playerCiv) } catch (_: Throwable) { false })
                t.put("explored", try { tile.isExplored(playerCiv) } catch (_: Throwable) { false })
            } else {
                t.put("visible", false)
                t.put("explored", false)
            }
            tilesArr.put(t)
        }
        mapObj.put("width", safeInt { tileMap.mapParameters.mapSize.width })
        mapObj.put("height", safeInt { tileMap.mapParameters.mapSize.height })
        mapObj.put("tiles", tilesArr)
        return mapObj
    }

    private fun extractPolicies(playerCiv: com.unciv.logic.civilization.Civilization?): JSONObject {
        val pol = JSONObject()
        if (playerCiv == null) {
            pol.put("adopted", JSONArray())
            pol.put("available", JSONArray())
            pol.put("can_adopt", false)
            pol.put("culture_needed", 0)
            return pol
        }
        val pm = playerCiv.policies
        val adopted = JSONArray()
        for (p in pm.adoptedPolicies.sorted()) { adopted.put(p) }
        pol.put("adopted", adopted)

        val available = JSONArray()
        try {
            val ruleset = playerCiv.gameInfo.ruleset
            for (p in ruleset.policies.values.filter { pm.isAdoptable(it) }.map { it.name }.sorted()) {
                available.put(p)
            }
        } catch (_: Throwable) {}
        pol.put("available", available)

        val canAdopt = try { pm.canAdoptPolicy() } catch (_: Throwable) { false }
        pol.put("can_adopt", canAdopt)
        val cultureNeeded = try { pm.getCultureNeededForNextPolicy() } catch (_: Throwable) { 0 }
        pol.put("culture_needed", cultureNeeded)
        return pol
    }

    private fun extractTech(playerCiv: com.unciv.logic.civilization.Civilization?): JSONObject {
        val tech = JSONObject()
        if (playerCiv == null) {
            tech.put("current_research", JSONObject.NULL)
            tech.put("turns_remaining", JSONObject.NULL)
            tech.put("researched", JSONArray())
            tech.put("available", JSONArray())
            return tech
        }
        val tm = playerCiv.tech
        val currentTech = try { tm.currentTechnologyName() } catch (_: Throwable) { null }
        tech.put("current_research", currentTech ?: JSONObject.NULL)
        if (currentTech != null) {
            val turnsStr = try { tm.turnsToTech(currentTech) } catch (_: Throwable) { "0" }
            tech.put("turns_remaining", turnsStr.toIntOrNull() ?: 0)
        } else {
            tech.put("turns_remaining", JSONObject.NULL)
        }
        val researched = JSONArray()
        for (t in tm.techsResearched.sorted()) { researched.put(t) }
        tech.put("researched", researched)
        val available = JSONArray()
        try {
            val ruleset = playerCiv.gameInfo.ruleset
            for (techName in ruleset.technologies.keys.sorted()) {
                if (!tm.isResearched(techName) && tm.canBeResearched(techName)) {
                    available.put(techName)
                }
            }
        } catch (_: Throwable) {}
        tech.put("available", available)
        return tech
    }

    private fun extractReligion(playerCiv: com.unciv.logic.civilization.Civilization?): JSONObject {
        val rel = JSONObject()
        if (playerCiv == null) {
            rel.put("status", "None")
            rel.put("religion_name", JSONObject.NULL)
            rel.put("beliefs", JSONArray())
            rel.put("available_pantheon_beliefs", JSONArray())
            rel.put("available_follower_beliefs", JSONArray())
            rel.put("available_founder_beliefs", JSONArray())
            rel.put("available_enhancer_beliefs", JSONArray())
            rel.put("can_found_pantheon", false)
            rel.put("holy_city", JSONObject.NULL)
            return rel
        }
        val rm = playerCiv.religionManager
        val gameInfo = playerCiv.gameInfo

        // Status
        val status = try { rm.religionState.name } catch (_: Throwable) { "None" }
        rel.put("status", status)

        // Religion name and beliefs
        val religion = rm.religion
        if (religion != null) {
            val displayName = try { religion.displayName ?: religion.name } catch (_: Throwable) { religion.name }
            rel.put("religion_name", displayName)
            val beliefs = JSONArray()
            try {
                for (b in religion.getAllBeliefsOrdered().map { it.name }.toList().sorted()) {
                    beliefs.put(b)
                }
            } catch (_: Throwable) {}
            rel.put("beliefs", beliefs)
        } else {
            rel.put("religion_name", JSONObject.NULL)
            rel.put("beliefs", JSONArray())
        }

        // Available beliefs by type (for when the agent needs to choose)
        val takenBeliefs = try {
            gameInfo.religions.values.flatMap { r ->
                r.getAllBeliefsOrdered().map { it.name }.toList()
            }.toSet()
        } catch (_: Throwable) { emptySet() }

        val pantheonBeliefs = JSONArray()
        val followerBeliefs = JSONArray()
        val founderBeliefs = JSONArray()
        val enhancerBeliefs = JSONArray()
        try {
            for (belief in gameInfo.ruleset.beliefs.values.sortedBy { it.name }) {
                if (belief.name in takenBeliefs) continue
                when (belief.type) {
                    BeliefType.Pantheon -> pantheonBeliefs.put(belief.name)
                    BeliefType.Follower -> followerBeliefs.put(belief.name)
                    BeliefType.Founder -> founderBeliefs.put(belief.name)
                    BeliefType.Enhancer -> enhancerBeliefs.put(belief.name)
                    else -> {}
                }
            }
        } catch (_: Throwable) {}
        rel.put("available_pantheon_beliefs", pantheonBeliefs)
        rel.put("available_follower_beliefs", followerBeliefs)
        rel.put("available_founder_beliefs", founderBeliefs)
        rel.put("available_enhancer_beliefs", enhancerBeliefs)

        // Can found pantheon
        val canPantheon = try { rm.canFoundOrExpandPantheon() } catch (_: Throwable) { false }
        rel.put("can_found_pantheon", canPantheon)

        // Holy city
        val holyCity = try { rm.getHolyCity()?.name } catch (_: Throwable) { null }
        rel.put("holy_city", holyCity ?: JSONObject.NULL)

        return rel
    }

    @JvmStatic fun advanceToNextTurn(gameInfo: GameInfo): GameInfo {
        gameInfo.nextTurn()
        return gameInfo
    }

    // ── ID-generation helpers ─────────────────────────────────────────

    private data class UnitWithId(val unit: com.unciv.logic.map.mapunit.MapUnit, val id: String)

    private fun buildPlayerUnitIds(gameInfo: GameInfo): List<UnitWithId> {
        val playerCiv = gameInfo.civilizations.firstOrNull {
            it.playerType == PlayerType.Human
        } ?: return emptyList()
        val civName = playerCiv.civName
        val unitsList = playerCiv.units.getCivUnits().filter { !it.isDestroyed }.toList().sortedWith(compareBy(
            { it.civ.civName },
            { it.name },
            { it.currentTile.position.x },
            { it.currentTile.position.y }
        ))
        val idCounts = mutableMapOf<String, Int>()
        val result = mutableListOf<UnitWithId>()
        for (unit in unitsList) {
            val x = unit.currentTile.position.x
            val y = unit.currentTile.position.y
            val baseId = "u:${unit.name}:${x},${y}:${civName}"
            val idx = idCounts.getOrDefault(baseId, 0)
            idCounts[baseId] = idx + 1
            val id = if (idx == 0) baseId else "${baseId}:${idx}"
            result.add(UnitWithId(unit, id))
        }
        return result
    }

    private fun buildCityId(city: com.unciv.logic.city.City): String {
        val x = city.location.x.toInt()
        val y = city.location.y.toInt()
        return "c:${city.name}:${x},${y}"
    }

    private data class EnemyUnitWithId(val unit: com.unciv.logic.map.mapunit.MapUnit, val id: String)

    private fun buildEnemyUnitIds(gameInfo: GameInfo): List<EnemyUnitWithId> {
        val playerCiv = gameInfo.civilizations.firstOrNull {
            it.playerType == PlayerType.Human
        } ?: return emptyList()
        val enemyUnits = mutableListOf<Pair<String, com.unciv.logic.map.mapunit.MapUnit>>()
        for (tile in playerCiv.viewableTiles) {
            for (unit in tile.getUnits()) {
                if (unit.civ == playerCiv) continue
                if (unit.isInvisible(playerCiv)) continue
                val x = tile.position.x
                val y = tile.position.y
                val baseId = "eu:${unit.name}:${x},${y}:${unit.civ.civName}"
                enemyUnits.add(baseId to unit)
            }
        }
        val sorted = enemyUnits.sortedWith(compareBy(
            { it.second.civ.civName },
            { it.second.name },
            { it.second.currentTile.position.x },
            { it.second.currentTile.position.y }
        ))
        val idCounts = mutableMapOf<String, Int>()
        val result = mutableListOf<EnemyUnitWithId>()
        for ((baseId, unit) in sorted) {
            val idx = idCounts.getOrDefault(baseId, 0)
            idCounts[baseId] = idx + 1
            val id = if (idx == 0) baseId else "${baseId}:${idx}"
            result.add(EnemyUnitWithId(unit, id))
        }
        return result
    }

    private fun buildEnemyCityId(city: com.unciv.logic.city.City): String {
        val x = city.getCenterTile().position.x
        val y = city.getCenterTile().position.y
        return "ec:${city.name}:${x},${y}"
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun trySetField(target: Any, name: String, value: Any) {
        try {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(target, value)
        } catch (_: Throwable) {}
    }

    private inline fun safeInt(block: () -> Int): Int = try { block() } catch (_: Throwable) { 0 }
    private inline fun safeString(block: () -> String?): String? = try { block() } catch (_: Throwable) { null }
}
