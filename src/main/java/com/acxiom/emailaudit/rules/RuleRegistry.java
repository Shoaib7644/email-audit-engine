package com.acxiom.emailaudit.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Thread-safe registry that stores and provides access to all {@link AuditRule}
 * strategy implementations.
 *
 * <h2>Ordering</h2>
 * <p>Rules are returned in registration order, allowing callers to control
 * execution sequence by the order in which they call
 * {@link #register(AuditRule)}.</p>
 *
 * <h2>Thread safety</h2>
 * <ul>
 *   <li>Insertion is backed by a {@link CopyOnWriteArrayList} (safe for
 *       infrequent writes, fast for frequent reads).</li>
 *   <li>Lookup by ID uses a {@link ConcurrentHashMap} for O(1) access.</li>
 *   <li>Both structures are kept in sync under a single write lock so that
 *       {@link #register} and {@link #deregister} are atomic with respect to
 *       each other.</li>
 * </ul>
 *
 * <h2>Duplicate policy</h2>
 * <p>Registering a rule whose {@link AuditRule#ruleId()} is already present
 * replaces the existing entry and logs a warning. This allows test overrides
 * without requiring a full registry rebuild.</p>
 */
public final class RuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuleRegistry.class);

    /**
     * Ordered list of registered rules – iteration order equals insertion order.
     * {@link CopyOnWriteArrayList} makes snapshot reads allocation-free.
     */
    private final CopyOnWriteArrayList<AuditRule> orderedRules = new CopyOnWriteArrayList<>();

    /**
     * Fast lookup by rule ID.
     * Always kept in sync with {@link #orderedRules}.
     */
    private final ConcurrentHashMap<String, AuditRule> ruleIndex = new ConcurrentHashMap<>();

    /**
     * Guards composite mutations that must be atomic across both
     * {@link #orderedRules} and {@link #ruleIndex}.
     */
    private final Object writeLock = new Object();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Registers {@code rule} at the end of the execution order.
     *
     * <p>If a rule with the same {@link AuditRule#ruleId()} is already
     * registered, it is replaced in-place (preserving its position in the
     * ordered list) and a warning is logged.</p>
     *
     * @param rule non-null rule to register
     * @throws IllegalArgumentException if {@code rule.ruleId()} is null or blank
     */
    public void register(final AuditRule rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        validateRuleId(rule);

        synchronized (writeLock) {
            final AuditRule existing = ruleIndex.get(rule.ruleId());

            if (existing != null) {
                // Replace in-place: update map and swap in the ordered list.
                ruleIndex.put(rule.ruleId(), rule);
                final int index = orderedRules.indexOf(existing);
                if (index >= 0) {
                    orderedRules.set(index, rule);
                }
                log.warn("Rule '{}' replaced existing registration – new: {}, old: {}",
                        rule.ruleId(), rule.getClass().getSimpleName(),
                        existing.getClass().getSimpleName());
            } else {
                ruleIndex.put(rule.ruleId(), rule);
                orderedRules.add(rule);
                log.info("Rule registered: '{}' [{}] – total: {}",
                        rule.ruleId(), rule.category(), orderedRules.size());
            }
        }
    }

    /**
     * Registers multiple rules in the order they appear in {@code rules}.
     *
     * @param rules non-null, non-empty list of rules
     */
    public void registerAll(final List<? extends AuditRule> rules) {
        Objects.requireNonNull(rules, "rules list must not be null");
        rules.forEach(this::register);
    }

    /**
     * Removes the rule with the given ID from the registry.
     *
     * @param ruleId ID of the rule to remove
     * @return {@code true} if a rule was found and removed
     */
    public boolean deregister(final String ruleId) {
        Objects.requireNonNull(ruleId, "ruleId must not be null");

        synchronized (writeLock) {
            final AuditRule removed = ruleIndex.remove(ruleId);
            if (removed == null) {
                log.debug("Deregister: rule '{}' not found", ruleId);
                return false;
            }
            orderedRules.remove(removed);
            log.info("Rule deregistered: '{}' – remaining: {}", ruleId, orderedRules.size());
            return true;
        }
    }

    /**
     * Returns all <em>enabled</em> rules in registration order.
     *
     * <p>The returned list is an unmodifiable snapshot; subsequent
     * registrations do not affect it.</p>
     *
     * @return unmodifiable list of enabled rules; never {@code null}
     */
    public List<AuditRule> getEnabledRules() {
        return orderedRules.stream()
                .filter(AuditRule::isEnabled)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Returns all rules (enabled and disabled) in registration order.
     *
     * @return unmodifiable snapshot; never {@code null}
     */
    public List<AuditRule> getAllRules() {
        return Collections.unmodifiableList(new ArrayList<>(orderedRules));
    }

    /**
     * Returns the rule registered under {@code ruleId}.
     *
     * @param ruleId the rule ID to look up
     * @return optional rule
     */
    public Optional<AuditRule> findById(final String ruleId) {
        if (ruleId == null) return Optional.empty();
        return Optional.ofNullable(ruleIndex.get(ruleId));
    }

    /**
     * Returns all enabled rules belonging to {@code category} in registration order.
     *
     * @param category category to filter by; must not be {@code null}
     * @return unmodifiable list; never {@code null}
     */
    public List<AuditRule> getByCategory(final AuditRule.RuleCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        return orderedRules.stream()
                .filter(AuditRule::isEnabled)
                .filter(r -> category == r.category())
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Returns a summary map of {@code category → count of enabled rules}.
     *
     * @return unmodifiable map; never {@code null}
     */
    public Map<AuditRule.RuleCategory, Long> countByCategory() {
        return orderedRules.stream()
                .filter(AuditRule::isEnabled)
                .collect(Collectors.groupingBy(
                        AuditRule::category,
                        Collectors.counting()));
    }

    /**
     * Returns {@code true} if no rules are registered.
     *
     * @return {@code true} when the registry is empty
     */
    public boolean isEmpty() {
        return orderedRules.isEmpty();
    }

    /**
     * Returns the total number of registered rules (enabled and disabled).
     *
     * @return rule count
     */
    public int size() {
        return orderedRules.size();
    }

    /**
     * Removes all registered rules.
     * Intended for test teardown.
     */
    public void clear() {
        synchronized (writeLock) {
            final int count = orderedRules.size();
            orderedRules.clear();
            ruleIndex.clear();
            log.info("RuleRegistry cleared – {} rule(s) removed", count);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static void validateRuleId(final AuditRule rule) {
        final String id = rule.ruleId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "AuditRule.ruleId() must not be null or blank for: "
                            + rule.getClass().getName());
        }
    }
}