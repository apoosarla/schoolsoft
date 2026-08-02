/**
 * Event bus — outbox + Spring Modulith's event publication. Domain events are
 * the contract between modules (per §3 principle 4). Producers write through
 * {@link com.schoolsoft.eventbus.api.DomainEvents}; consumers use
 * {@code @ApplicationModuleListener}.
 *
 * The chain-scoped outbox table backs replay + audit. Spring Modulith's own
 * event_publication table handles in-process delivery + retry.
 */
@org.springframework.modulith.ApplicationModule(displayName = "EventBus")
package com.schoolsoft.eventbus;
