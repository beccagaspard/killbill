/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2016 Groupon, Inc
 * Copyright 2014-2016 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.entitlement.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.catalog.api.BillingActionPolicy;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.Plan;
import org.killbill.billing.catalog.api.PlanPhase;
import org.killbill.billing.catalog.api.PlanPhasePriceOverride;
import org.killbill.billing.catalog.api.PriceList;
import org.killbill.billing.catalog.api.Product;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.entitlement.DefaultEntitlementService;
import org.killbill.billing.entitlement.EntitlementService;
import org.killbill.billing.entitlement.EventsStream;
import org.killbill.billing.entitlement.api.EntitlementPluginExecution.WithEntitlementPlugin;
import org.killbill.billing.entitlement.block.BlockingChecker;
import org.killbill.billing.entitlement.dao.BlockingStateDao;
import org.killbill.billing.entitlement.engine.core.EntitlementNotificationKey;
import org.killbill.billing.entitlement.engine.core.EntitlementNotificationKeyAction;
import org.killbill.billing.entitlement.engine.core.EntitlementUtils;
import org.killbill.billing.entitlement.engine.core.EventsStreamBuilder;
import org.killbill.billing.entitlement.plugin.api.EntitlementContext;
import org.killbill.billing.entitlement.plugin.api.OperationType;
import org.killbill.billing.entity.EntityBase;
import org.killbill.billing.junction.DefaultBlockingState;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.security.Logical;
import org.killbill.billing.security.Permission;
import org.killbill.billing.security.SecurityApiException;
import org.killbill.billing.security.api.SecurityApi;
import org.killbill.billing.subscription.api.SubscriptionBase;
import org.killbill.billing.subscription.api.SubscriptionBaseInternalApi;
import org.killbill.billing.subscription.api.user.SubscriptionBaseApiException;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.InternalCallContextFactory;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.clock.Clock;
import org.killbill.notificationq.api.NotificationEvent;
import org.killbill.notificationq.api.NotificationQueue;
import org.killbill.notificationq.api.NotificationQueueService;
import org.killbill.notificationq.api.NotificationQueueService.NoSuchNotificationQueue;

import com.google.common.collect.ImmutableList;

public class DefaultEntitlement extends EntityBase implements Entitlement {

    private final SecurityApi securityApi;
    protected final EventsStreamBuilder eventsStreamBuilder;
    protected final EntitlementDateHelper dateHelper;
    protected final InternalCallContextFactory internalCallContextFactory;
    protected final Clock clock;
    protected final BlockingChecker checker;
    protected final EntitlementApi entitlementApi;
    protected final EntitlementPluginExecution pluginExecution;
    protected final SubscriptionBaseInternalApi subscriptionInternalApi;
    protected final BlockingStateDao blockingStateDao;
    protected final NotificationQueueService notificationQueueService;
    protected final EntitlementUtils entitlementUtils;

    // Refresh-able
    protected EventsStream eventsStream;


    public DefaultEntitlement(final UUID entitlementId, final EventsStreamBuilder eventsStreamBuilder,
                              final EntitlementApi entitlementApi, final EntitlementPluginExecution pluginExecution, final BlockingStateDao blockingStateDao,
                              final SubscriptionBaseInternalApi subscriptionInternalApi, final BlockingChecker checker,
                              final NotificationQueueService notificationQueueService, final EntitlementUtils entitlementUtils,
                              final EntitlementDateHelper dateHelper, final Clock clock, final SecurityApi securityApi,
                              final InternalCallContextFactory internalCallContextFactory, final TenantContext tenantContext) throws EntitlementApiException {
        this(eventsStreamBuilder.buildForEntitlement(entitlementId, tenantContext), eventsStreamBuilder,
             entitlementApi, pluginExecution, blockingStateDao, subscriptionInternalApi, checker, notificationQueueService,
             entitlementUtils, dateHelper, clock, securityApi, internalCallContextFactory);
    }

    public DefaultEntitlement(final EventsStream eventsStream, final EventsStreamBuilder eventsStreamBuilder,
                              final EntitlementApi entitlementApi, final EntitlementPluginExecution pluginExecution, final BlockingStateDao blockingStateDao,
                              final SubscriptionBaseInternalApi subscriptionInternalApi, final BlockingChecker checker,
                              final NotificationQueueService notificationQueueService, final EntitlementUtils entitlementUtils,
                              final EntitlementDateHelper dateHelper, final Clock clock, final SecurityApi securityApi, final InternalCallContextFactory internalCallContextFactory) {
        super(eventsStream.getEntitlementId(), eventsStream.getSubscriptionBase().getCreatedDate(), eventsStream.getSubscriptionBase().getUpdatedDate());
        this.eventsStreamBuilder = eventsStreamBuilder;
        this.eventsStream = eventsStream;
        this.dateHelper = dateHelper;
        this.entitlementApi = entitlementApi;
        this.pluginExecution = pluginExecution;
        this.subscriptionInternalApi = subscriptionInternalApi;
        this.internalCallContextFactory = internalCallContextFactory;
        this.clock = clock;
        this.securityApi = securityApi;
        this.checker = checker;
        this.blockingStateDao = blockingStateDao;
        this.notificationQueueService = notificationQueueService;
        this.entitlementUtils = entitlementUtils;
    }

    public DefaultEntitlement(final DefaultEntitlement in) {
        this(in.getEventsStream(),
             in.getEventsStreamBuilder(),
             in.getEntitlementApi(),
             in.getPluginExecution(),
             in.getBlockingStateDao(),
             in.getSubscriptionInternalApi(),
             in.getChecker(),
             in.getNotificationQueueService(),
             in.getEntitlementUtils(),
             in.getDateHelper(),
             in.getClock(),
             in.getSecurityApi(),
             in.getInternalCallContextFactory());
    }

    public EventsStream getEventsStream() {
        return eventsStream;
    }

    public DateTimeZone getAccountTimeZone() {
        return eventsStream.getAccountTimeZone();
    }

    // Subscription associated with this entitlement (equals to baseSubscription for base subscriptions)
    public SubscriptionBase getSubscriptionBase() {
        return eventsStream.getSubscriptionBase();
    }

    // Base subscription for the bundle if it exists, null otherwise
    public SubscriptionBase getBasePlanSubscriptionBase() {
        return eventsStream.getBasePlanSubscriptionBase();
    }

    public EventsStreamBuilder getEventsStreamBuilder() {
        return eventsStreamBuilder;
    }

    public EntitlementDateHelper getDateHelper() {
        return dateHelper;
    }

    public InternalCallContextFactory getInternalCallContextFactory() {
        return internalCallContextFactory;
    }

    public EntitlementApi getEntitlementApi() {
        return entitlementApi;
    }

    public EntitlementPluginExecution getPluginExecution() {
        return pluginExecution;
    }

    public SubscriptionBaseInternalApi getSubscriptionInternalApi() {
        return subscriptionInternalApi;
    }

    public Clock getClock() {
        return clock;
    }

    public BlockingChecker getChecker() {
        return checker;
    }

    public BlockingStateDao getBlockingStateDao() {
        return blockingStateDao;
    }

    public NotificationQueueService getNotificationQueueService() {
        return notificationQueueService;
    }

    public EntitlementUtils getEntitlementUtils() {
        return entitlementUtils;
    }

    public SecurityApi getSecurityApi() {
        return securityApi;
    }

    @Override
    public UUID getBaseEntitlementId() {
        return eventsStream.getEntitlementId();
    }

    @Override
    public UUID getBundleId() {
        return eventsStream.getBundleId();
    }

    @Override
    public UUID getAccountId() {
        return eventsStream.getAccountId();
    }

    @Override
    public String getExternalKey() {
        return eventsStream.getBundleExternalKey();
    }

    @Override
    public EntitlementState getState() {
        return eventsStream.getEntitlementState();
    }

    @Override
    public EntitlementSourceType getSourceType() {
        return getSubscriptionBase().getSourceType();
    }

    @Override
    public LocalDate getEffectiveStartDate() {
        return new LocalDate(getSubscriptionBase().getStartDate(), eventsStream.getAccountTimeZone());
    }

    @Override
    public LocalDate getEffectiveEndDate() {
        return eventsStream.getEntitlementEffectiveEndDate();
    }

    @Override
    public Product getLastActiveProduct() {
        return getSubscriptionBase().getLastActiveProduct();
    }

    @Override
    public Plan getLastActivePlan() {
        return getSubscriptionBase().getLastActivePlan();
    }

    @Override
    public PlanPhase getLastActivePhase() {
        return getSubscriptionBase().getLastActivePhase();
    }

    @Override
    public PriceList getLastActivePriceList() {
        return getSubscriptionBase().getLastActivePriceList();
    }

    @Override
    public ProductCategory getLastActiveProductCategory() {
        return getSubscriptionBase().getLastActiveCategory();
    }


    @Override
    public Entitlement cancelEntitlementWithPolicy(final EntitlementActionPolicy entitlementPolicy, final Iterable<PluginProperty> properties, final CallContext callContext) throws EntitlementApiException {

        // Get the latest state from disk - required to have the latest CTD
        refresh(callContext);

        final LocalDate cancellationDate = getLocalDateFromEntitlementPolicy(entitlementPolicy);
        return cancelEntitlementWithDate(cancellationDate, false, properties, callContext);
    }

    @Override
    public Entitlement cancelEntitlementWithDate(final LocalDate localCancelDate, final boolean overrideBillingEffectiveDate, final Iterable<PluginProperty> properties, final CallContext callContext) throws EntitlementApiException {

        checkForPermissions(Permission.ENTITLEMENT_CAN_CANCEL, callContext);

        // Get the latest state from disk
        refresh(callContext);

        final EntitlementContext pluginContext = new DefaultEntitlementContext(OperationType.CANCEL_SUBSCRIPTION,
                                                                               getAccountId(),
                                                                               null,
                                                                               getBundleId(),
                                                                               getExternalKey(),
                                                                               null,
                                                                               localCancelDate,
                                                                               properties,
                                                                               callContext);


        final WithEntitlementPlugin<Entitlement> cancelEntitlementWithPlugin = new WithEntitlementPlugin<Entitlement>() {

            @Override
            public Entitlement doCall(final EntitlementApi entitlementApi, final EntitlementContext updatedPluginContext) throws EntitlementApiException {
                if (eventsStream.isEntitlementCancelled()) {
                    throw new EntitlementApiException(ErrorCode.SUB_CANCEL_BAD_STATE, getId(), EntitlementState.CANCELLED);
                }

                final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(getAccountId(), callContext);
                final DateTime effectiveCancelDate = dateHelper.fromLocalDateAndReferenceTime(localCancelDate, getSubscriptionBase().getStartDate(), contextWithValidAccountRecordId);
                try {
                    if (overrideBillingEffectiveDate) {
                        getSubscriptionBase().cancelWithDate(effectiveCancelDate, callContext);
                    } else {
                        getSubscriptionBase().cancel(callContext);
                    }
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e);
                }

                final BlockingState newBlockingState = new DefaultBlockingState(getId(), BlockingStateType.SUBSCRIPTION, DefaultEntitlementApi.ENT_STATE_CANCELLED, EntitlementService.ENTITLEMENT_SERVICE_NAME, true, true, false, effectiveCancelDate);
                final Collection<NotificationEvent> notificationEvents = new ArrayList<NotificationEvent>();
                final Collection<BlockingState> addOnsBlockingStates = computeAddOnBlockingStates(effectiveCancelDate, notificationEvents, callContext, contextWithValidAccountRecordId);

                // Record the new state first, then insert the notifications to avoid race conditions
                setBlockingStates(newBlockingState, addOnsBlockingStates, contextWithValidAccountRecordId);
                for (final NotificationEvent notificationEvent : notificationEvents) {
                    recordFutureNotification(effectiveCancelDate, notificationEvent, contextWithValidAccountRecordId);
                }

                return entitlementApi.getEntitlementForId(getId(), callContext);
            }
        };

        return pluginExecution.executeWithPlugin(cancelEntitlementWithPlugin, pluginContext);
    }


    @Override
    public void uncancelEntitlement(final Iterable<PluginProperty> properties, final CallContext callContext) throws EntitlementApiException {

        checkForPermissions(Permission.ENTITLEMENT_CAN_CANCEL, callContext);

        // Get the latest state from disk
        refresh(callContext);

        if (eventsStream.isSubscriptionCancelled()) {
            throw new EntitlementApiException(ErrorCode.SUB_UNCANCEL_BAD_STATE, getId());
        }

        final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(getAccountId(), callContext);
        final Collection<BlockingState> pendingEntitlementCancellationEvents = eventsStream.getPendingEntitlementCancellationEvents();
        if (eventsStream.isEntitlementCancelled()) {
            final BlockingState cancellationEvent = eventsStream.getEntitlementCancellationEvent();
            blockingStateDao.unactiveBlockingState(cancellationEvent.getId(), contextWithValidAccountRecordId);
        } else if (pendingEntitlementCancellationEvents.size() > 0) {
            // Reactivate entitlements
            // See also https://github.com/killbill/killbill/issues/111
            //
            // Today we only support cancellation at SUBSCRIPTION level (Not ACCOUNT or BUNDLE), so we should really have only
            // one future event in the list
            //
            for (final BlockingState futureCancellation : pendingEntitlementCancellationEvents) {
                blockingStateDao.unactiveBlockingState(futureCancellation.getId(), contextWithValidAccountRecordId);
            }
        } else {
            // Entitlement is NOT cancelled (or future cancelled), there is nothing to do
            throw new EntitlementApiException(ErrorCode.SUB_UNCANCEL_BAD_STATE, getId());
        }

        // If billing was previously cancelled, reactivate
        if (getSubscriptionBase().getFutureEndDate() != null) {
            try {
                getSubscriptionBase().uncancel(callContext);
            } catch (final SubscriptionBaseApiException e) {
                throw new EntitlementApiException(e);
            }
        }
    }

    @Override
    public Entitlement cancelEntitlementWithPolicyOverrideBillingPolicy(final EntitlementActionPolicy entitlementPolicy, final BillingActionPolicy billingPolicy, final Iterable<PluginProperty> properties, final CallContext callContext) throws EntitlementApiException {
        // Get the latest state from disk - required to have the latest CTD
        refresh(callContext);

        final LocalDate cancellationDate = getLocalDateFromEntitlementPolicy(entitlementPolicy);
        return cancelEntitlementWithDateOverrideBillingPolicy(cancellationDate, billingPolicy, properties, callContext);
    }


    // See also EntitlementInternalApi#cancel for the bulk API
    @Override
    public Entitlement cancelEntitlementWithDateOverrideBillingPolicy(final LocalDate localCancelDate, final BillingActionPolicy billingPolicy, final Iterable<PluginProperty> properties, final CallContext callContext) throws EntitlementApiException {

        checkForPermissions(Permission.ENTITLEMENT_CAN_CANCEL, callContext);

        // Get the latest state from disk
        refresh(callContext);

        final EntitlementContext pluginContext = new DefaultEntitlementContext(OperationType.CANCEL_SUBSCRIPTION,
                                                                               getAccountId(),
                                                                               null,
                                                                               getBundleId(),
                                                                               getExternalKey(),
                                                                               null,
                                                                               localCancelDate,
                                                                               properties,
                                                                               callContext);

        final WithEntitlementPlugin<Entitlement> cancelEntitlementWithPlugin = new WithEntitlementPlugin<Entitlement>() {
            @Override
            public Entitlement doCall(final EntitlementApi entitlementApi, final EntitlementContext updatedPluginContext) throws EntitlementApiException {
                if (eventsStream.isEntitlementCancelled()) {
                    throw new EntitlementApiException(ErrorCode.SUB_CANCEL_BAD_STATE, getId(), EntitlementState.CANCELLED);
                }

                // Make sure to compute the entitlement effective date first to avoid timing issues for IMM cancellations
                // (we don't want an entitlement cancel date one second or so after the subscription cancel date or add-ons cancellations
                // computations won't work).
                final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(getAccountId(), callContext);
                final LocalDate effectiveLocalDate = new LocalDate(updatedPluginContext.getEffectiveDate(), eventsStream.getAccountTimeZone());
                final DateTime effectiveDate = dateHelper.fromLocalDateAndReferenceTime(effectiveLocalDate, getSubscriptionBase().getStartDate(), contextWithValidAccountRecordId);

                try {
                    // Cancel subscription base first, to correctly compute the add-ons entitlements we need to cancel (see below)
                    getSubscriptionBase().cancelWithPolicy(billingPolicy, callContext);
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e);
                }

                final BlockingState newBlockingState = new DefaultBlockingState(getId(), BlockingStateType.SUBSCRIPTION, DefaultEntitlementApi.ENT_STATE_CANCELLED, EntitlementService.ENTITLEMENT_SERVICE_NAME, true, true, false, effectiveDate);
                final Collection<NotificationEvent> notificationEvents = new ArrayList<NotificationEvent>();
                final Collection<BlockingState> addOnsBlockingStates = computeAddOnBlockingStates(effectiveDate, notificationEvents, callContext, contextWithValidAccountRecordId);

                // Record the new state first, then insert the notifications to avoid race conditions
                setBlockingStates(newBlockingState, addOnsBlockingStates, contextWithValidAccountRecordId);
                for (final NotificationEvent notificationEvent : notificationEvents) {
                    recordFutureNotification(effectiveDate, notificationEvent, contextWithValidAccountRecordId);
                }

                return entitlementApi.getEntitlementForId(getId(), callContext);
            }
        };
        return pluginExecution.executeWithPlugin(cancelEntitlementWithPlugin, pluginContext);
    }

    private LocalDate getLocalDateFromEntitlementPolicy(final EntitlementActionPolicy entitlementPolicy) {
        final LocalDate cancellationDate;
        switch (entitlementPolicy) {
            case IMMEDIATE:
                cancellationDate = new LocalDate(clock.getUTCNow(), eventsStream.getAccountTimeZone());
                break;
            case END_OF_TERM:
                cancellationDate = getSubscriptionBase().getChargedThroughDate() != null ? new LocalDate(getSubscriptionBase().getChargedThroughDate(), eventsStream.getAccountTimeZone()) : new LocalDate(clock.getUTCNow(), eventsStream.getAccountTimeZone());
                break;
            default:
                throw new RuntimeException("Unsupported policy " + entitlementPolicy);
        }
        return (cancellationDate.compareTo(getEffectiveStartDate()) < 0) ? getEffectiveStartDate() : cancellationDate;
    }

    @Override
    public Entitlement changePlan(final String productName, final BillingPeriod billingPeriod, final String priceList, final List<PlanPhasePriceOverride> overrides, final Iterable<PluginProperty> properties, final CallContext callContext) throws EntitlementApiException {


        checkForPermissions(Permission.ENTITLEMENT_CAN_CHANGE_PLAN, callContext);

        // Get the latest state from disk
        refresh(callContext);

        final EntitlementContext pluginContext = new DefaultEntitlementContext(OperationType.CHANGE_PLAN,
                                                                               getAccountId(),
                                                                               null,
                                                                               getBundleId(),
                                                                               getExternalKey(),
                                                                               null,
                                                                               null,
                                                                               properties,
                                                                               callContext);

        final WithEntitlementPlugin<Entitlement> changePlanWithPlugin = new WithEntitlementPlugin<Entitlement>() {
            @Override
            public Entitlement doCall(final EntitlementApi entitlementApi, final EntitlementContext updatedPluginContext) throws EntitlementApiException {
                if (!eventsStream.isEntitlementActive()) {
                    throw new EntitlementApiException(ErrorCode.SUB_CHANGE_NON_ACTIVE, getId(), getState());
                }

                final InternalCallContext context = internalCallContextFactory.createInternalCallContext(getAccountId(), callContext);

                final DateTime effectiveChangeDate;
                try {
                    effectiveChangeDate = subscriptionInternalApi.getDryRunChangePlanEffectiveDate(getSubscriptionBase(), productName, billingPeriod, priceList, null, null, context);
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e, e.getCode(), e.getMessage());
                }

                try {
                    checker.checkBlockedChange(getSubscriptionBase(), effectiveChangeDate, context);
                } catch (final BlockingApiException e) {
                    throw new EntitlementApiException(e, e.getCode(), e.getMessage());
                }

                try {
                    getSubscriptionBase().changePlan(productName, billingPeriod, priceList, overrides, callContext);
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e);
                }

                final Collection<NotificationEvent> notificationEvents = new ArrayList<NotificationEvent>();
                final Iterable<BlockingState> addOnsBlockingStates = computeAddOnBlockingStates(effectiveChangeDate, notificationEvents, callContext, context);

                // Record the new state first, then insert the notifications to avoid race conditions
                setBlockingStates(addOnsBlockingStates, context);
                for (final NotificationEvent notificationEvent : notificationEvents) {
                    recordFutureNotification(effectiveChangeDate, notificationEvent, context);
                }
                return entitlementApi.getEntitlementForId(getId(), callContext);
            }
        };
        return pluginExecution.executeWithPlugin(changePlanWithPlugin, pluginContext);
    }

    @Override
    public Entitlement changePlanWithDate(final String productName, final BillingPeriod billingPeriod, final String priceList, final List<PlanPhasePriceOverride> overrides, final LocalDate localDate, final Iterable<PluginProperty> properties, final CallContext callContext) throws EntitlementApiException {

        checkForPermissions(Permission.ENTITLEMENT_CAN_CHANGE_PLAN, callContext);

        // Get the latest state from disk
        refresh(callContext);

        final EntitlementContext pluginContext = new DefaultEntitlementContext(OperationType.CHANGE_PLAN,
                                                                               getAccountId(),
                                                                               null,
                                                                               getBundleId(),
                                                                               getExternalKey(),
                                                                               null,
                                                                               localDate,
                                                                               properties,
                                                                               callContext);

        final WithEntitlementPlugin<Entitlement> changePlanWithPlugin = new WithEntitlementPlugin<Entitlement>() {
            @Override
            public Entitlement doCall(final EntitlementApi entitlementApi, final EntitlementContext updatedPluginContext) throws EntitlementApiException {
                if (!eventsStream.isEntitlementActive()) {
                    throw new EntitlementApiException(ErrorCode.SUB_CHANGE_NON_ACTIVE, getId(), getState());
                }

                final InternalCallContext context = internalCallContextFactory.createInternalCallContext(getAccountId(), callContext);
                final DateTime effectiveChangeDateComputed = dateHelper.fromLocalDateAndReferenceTime(updatedPluginContext.getEffectiveDate(), getSubscriptionBase().getStartDate(), context);

                final DateTime effectiveChangeDate;
                try {
                    effectiveChangeDate = subscriptionInternalApi.getDryRunChangePlanEffectiveDate(getSubscriptionBase(), productName, billingPeriod, priceList, effectiveChangeDateComputed, null, context);
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e, e.getCode(), e.getMessage());
                }

                try {
                    checker.checkBlockedChange(getSubscriptionBase(), effectiveChangeDate, context);
                } catch (final BlockingApiException e) {
                    throw new EntitlementApiException(e, e.getCode(), e.getMessage());
                }

                try {
                    getSubscriptionBase().changePlanWithDate(productName, billingPeriod, priceList, overrides, effectiveChangeDate, callContext);
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e);
                }

                final Collection<NotificationEvent> notificationEvents = new ArrayList<NotificationEvent>();
                final Iterable<BlockingState> addOnsBlockingStates = computeAddOnBlockingStates(effectiveChangeDate, notificationEvents, callContext, context);

                // Record the new state first, then insert the notifications to avoid race conditions
                setBlockingStates(addOnsBlockingStates, context);
                for (final NotificationEvent notificationEvent : notificationEvents) {
                    recordFutureNotification(effectiveChangeDate, notificationEvent, context);
                }

                return entitlementApi.getEntitlementForId(getId(), callContext);
            }
        };
        return pluginExecution.executeWithPlugin(changePlanWithPlugin, pluginContext);
    }

    @Override
    public Entitlement changePlanOverrideBillingPolicy(final String productName, final BillingPeriod billingPeriod, final String priceList, final List<PlanPhasePriceOverride> overrides, final LocalDate localDate, final BillingActionPolicy actionPolicy, final Iterable<PluginProperty> properties, final CallContext callContext) throws EntitlementApiException {

        checkForPermissions(Permission.ENTITLEMENT_CAN_CHANGE_PLAN, callContext);

        // Get the latest state from disk
        refresh(callContext);

        final EntitlementContext pluginContext = new DefaultEntitlementContext(OperationType.CHANGE_PLAN,
                                                                               getAccountId(),
                                                                               null,
                                                                               getBundleId(),
                                                                               getExternalKey(),
                                                                               null,
                                                                               localDate,
                                                                               properties,
                                                                               callContext);

        final WithEntitlementPlugin<Entitlement> changePlanWithPlugin = new WithEntitlementPlugin<Entitlement>() {
            @Override
            public Entitlement doCall(final EntitlementApi entitlementApi, final EntitlementContext updatedPluginContext) throws EntitlementApiException {
                if (!eventsStream.isEntitlementActive()) {
                    throw new EntitlementApiException(ErrorCode.SUB_CHANGE_NON_ACTIVE, getId(), getState());
                }

                final InternalCallContext context = internalCallContextFactory.createInternalCallContext(getAccountId(), callContext);

                final DateTime effectiveChangeDate;
                try {
                    effectiveChangeDate = subscriptionInternalApi.getDryRunChangePlanEffectiveDate(getSubscriptionBase(), productName, billingPeriod, priceList, null, actionPolicy, context);
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e, e.getCode(), e.getMessage());
                }

                try {
                    checker.checkBlockedChange(getSubscriptionBase(), effectiveChangeDate, context);
                } catch (final BlockingApiException e) {
                    throw new EntitlementApiException(e, e.getCode(), e.getMessage());
                }

                try {
                    getSubscriptionBase().changePlanWithPolicy(productName, billingPeriod, priceList, overrides, actionPolicy, callContext);
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e);
                }

                final Collection<NotificationEvent> notificationEvents = new ArrayList<NotificationEvent>();
                final Iterable<BlockingState> addOnsBlockingStates = computeAddOnBlockingStates(effectiveChangeDate, notificationEvents, callContext, context);

                // Record the new state first, then insert the notifications to avoid race conditions
                setBlockingStates(addOnsBlockingStates, context);
                for (final NotificationEvent notificationEvent : notificationEvents) {
                    recordFutureNotification(effectiveChangeDate, notificationEvent, context);
                }

                return entitlementApi.getEntitlementForId(getId(), callContext);
            }
        };
        return pluginExecution.executeWithPlugin(changePlanWithPlugin, pluginContext);
    }

    private void refresh(final TenantContext context) throws EntitlementApiException {
        eventsStream = eventsStreamBuilder.refresh(eventsStream, context);
    }

    public Collection<BlockingState> computeAddOnBlockingStates(final DateTime effectiveDate, final Collection<NotificationEvent> notificationEvents, final TenantContext context, final InternalCallContext internalCallContext) throws EntitlementApiException {
        // Optimization - bail early
        if (!ProductCategory.BASE.equals(getSubscriptionBase().getCategory())) {
            // Only base subscriptions have add-ons
            return ImmutableList.<BlockingState>of();
        }

        // Get the latest state from disk (we just got cancelled or changed plan)
        refresh(context);

        // If cancellation/change occurs in the future, do nothing for now but add a notification entry.
        // This is to distinguish whether a future cancellation was requested by the user, or was a side effect
        // (e.g. base plan cancellation): future entitlement cancellations for add-ons on disk always reflect
        // an explicit cancellation. This trick lets us determine what to do when un-cancelling.
        // This mirror the behavior in subscription base (see DefaultSubscriptionBaseApiService).
        final DateTime now = clock.getUTCNow();
        if (effectiveDate.compareTo(now) > 0) {
            // Note that usually we record the notification from the DAO. We cannot do it here because not all calls
            // go through the DAO (e.g. change)
            final boolean isBaseEntitlementCancelled = eventsStream.isEntitlementCancelled();
            final NotificationEvent notificationEvent = new EntitlementNotificationKey(getId(), getBundleId(), isBaseEntitlementCancelled ? EntitlementNotificationKeyAction.CANCEL : EntitlementNotificationKeyAction.CHANGE, effectiveDate);
            notificationEvents.add(notificationEvent);
            return ImmutableList.<BlockingState>of();
        }

        return eventsStream.computeAddonsBlockingStatesForNextSubscriptionBaseEvent(effectiveDate);
    }

    private void recordFutureNotification(final DateTime effectiveDate,
                                          final NotificationEvent notificationEvent,
                                          final InternalCallContext context) {
        try {
            final NotificationQueue subscriptionEventQueue = notificationQueueService.getNotificationQueue(DefaultEntitlementService.ENTITLEMENT_SERVICE_NAME,
                                                                                                           DefaultEntitlementService.NOTIFICATION_QUEUE_NAME);
            subscriptionEventQueue.recordFutureNotification(effectiveDate, notificationEvent, context.getUserToken(), context.getAccountRecordId(), context.getTenantRecordId());
        } catch (final NoSuchNotificationQueue e) {
            throw new RuntimeException(e);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void setBlockingStates(final BlockingState entitlementBlockingState, final Collection<BlockingState> addOnsBlockingStates, final InternalCallContext internalCallContext) {
        final Collection<BlockingState> states = new LinkedList<BlockingState>();
        states.add(entitlementBlockingState);
        states.addAll(addOnsBlockingStates);
        setBlockingStates(states, internalCallContext);
    }

    private void setBlockingStates(final Iterable<BlockingState> blockingStates, final InternalCallContext internalCallContext) {
        entitlementUtils.setBlockingStatesAndPostBlockingTransitionEvent(blockingStates, getBundleId(), internalCallContext);
    }

    //
    // Unfortunately the permission checks for the entitlement api cannot *simply* rely on the KillBillShiroAopModule because some of the operations (CANCEL, CHANGE) are
    // done through objects that are not injected by Guice, and so the check needs to happen explicitly.
    //
    private void checkForPermissions(final Permission permission, final TenantContext callContext) throws EntitlementApiException {
        //
        // If authentication had been done (CorsBasicHttpAuthenticationFilter) we verify the correct permissions exist.
        //
        if (securityApi.isSubjectAuthenticated()) {
            try {
                securityApi.checkCurrentUserPermissions(ImmutableList.of(permission), Logical.AND, callContext);
            } catch (final SecurityApiException e) {
                throw new EntitlementApiException(ErrorCode.SECURITY_NOT_ENOUGH_PERMISSIONS);
            }
        }
    }
}
