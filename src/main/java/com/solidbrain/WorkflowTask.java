package com.solidbrain;

import com.getbase.Client;
import com.getbase.Configuration;
import com.getbase.models.*;
import com.getbase.services.DealsService;
import com.getbase.services.StagesService;
import com.getbase.services.UsersService;
import com.getbase.sync.Meta;
import com.getbase.sync.Sync;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static java.util.stream.Collectors.toList;

/**
 * Created by Krzysztof Wilk on 06/09/16.
 */
@Component
@Slf4j
class WorkflowTask {
    private List<String> salesRepresentativesEmails;
    private List<String> accountManagersEmails;

    private String accountManagerOnDutyEmail;

    private String dealNameDateFormat;

    private final Client baseClient;

    private final String deviceUuid;

    @Autowired
    public WorkflowTask(@Value("${workflow.deal.name.date.format}") String dealNameDateFormat) {
        this.dealNameDateFormat = dealNameDateFormat;

        String accessToken = getAccessToken();

        baseClient = new Client(new Configuration.Builder()
                                        .accessToken(accessToken)
                                        .build());

        deviceUuid = getDeviceUuid();

        accountManagersEmails = getEmailsOfAccountManagers();
        log.debug("accountManagersEmails=" + accountManagersEmails);

        accountManagerOnDutyEmail = getAccountManagerOnDuty();
        log.debug("accountManagerOnDutyEmail=" + accountManagerOnDutyEmail);

        salesRepresentativesEmails = getEmailsOfSalesRepresentatives();
        log.debug("salesRepresentativesEmails=" + salesRepresentativesEmails);
    }

    /**
     * Main workflow loop
     */
    @Scheduled(fixedDelayString = "${workflow.loop.interval}")
    public void runWorkflow() {
        log.info("Starting workflow run");

        Sync sync = new Sync(baseClient, deviceUuid);

        // Workaround: https://gist.github.com/michal-mally/73ea265718a0d29aac350dd81528414f
        sync.subscribe(Account.class, (meta, account) -> true)
                .subscribe(Address.class, (meta, address) -> true)
                .subscribe(AssociatedContact.class, (meta, associatedContact) -> true)
                .subscribe(Contact.class, this::processContact)
                .subscribe(Deal.class, this::processDeal)
                .subscribe(LossReason.class, (meta, lossReason) -> true)
                .subscribe(Note.class, (meta, note) -> true)
                .subscribe(Pipeline.class, (meta, pipeline) -> true)
                .subscribe(Source.class, (meta, source) -> true)
                .subscribe(Stage.class, (meta, stage) -> true)
                .subscribe(Tag.class, (meta, tag) -> true)
                .subscribe(Task.class, (meta, task) -> true)
                .subscribe(User.class, (meta, user) -> true)
                .subscribe(Lead.class, (meta, lead) -> true)
                .fetch();
    }

    @SuppressWarnings("squid:S1192")
    private boolean processContact(final Meta meta, final Contact contact) {
        MDC.put("contactId", contact.getId().toString());
        log.trace("Processing current contact");

        String eventType = meta.getSync().getEventType();
        log.trace("eventType={}", eventType);
        if (eventType.contentEquals("created") || eventType.contentEquals("updated")) {
            log.debug("Contact sync eventType={}", eventType);

            MDC.clear();
            try {
                if (shouldNewDealBeCreated(contact)) {
                    createNewDeal(contact);
                }
            } catch (RuntimeException e) {
                log.error("Cannot process contact (id={}). Message={})", contact.getId(), e.getMessage(), e);
            }

        }
        return true;
    }

    private boolean processDeal(final Meta meta, final Deal deal) {
        MDC.put("dealId", deal.getId().toString());
        log.trace("Processing current deal");

        String eventType = meta.getSync().getEventType();
        log.trace("Event type={}", eventType);
        if (eventType.contentEquals("created") || eventType.contentEquals("updated")) {
            log.debug("Deal sync event type={}", eventType);

            try {
                processRecentlyModifiedDeal(deal);
            } catch (RuntimeException e) {
                log.error("Cannot process deal (id={}). Message={})", deal.getId(), e.getMessage(), e);
            }
        }
        return true;
    }

    private void processRecentlyModifiedDeal(final Deal deal) {
        log.trace("Processing recently modified deal");

        if (isDealStageWon(deal)) {
            log.info("Verifying deal in Won stage");

            MDC.clear();
            Contact dealsContact = fetchExistingContact(deal.getContactId());
            log.trace("Deal's contact={}", dealsContact);

            User contactOwner = fetchOwner(dealsContact.getOwnerId());
            log.trace("Contact's owner={}", contactOwner);

            if (!isContactOwnerAnAccountManager(contactOwner)) {
                updateExistingContact(dealsContact);
            }
        }
    }

    @SuppressWarnings("squid:S1192")
    private boolean updateExistingContact(final Contact dealsContact) {
        MDC.put("contactId", dealsContact.getId().toString());
        log.info("Updating contact's owner");
        MDC.clear();

        User accountManager = getUserByEmail(accountManagerOnDutyEmail)
                                .orElseThrow(() -> new MissingResourceException("User not found",
                                                                                "com.getbase.models.User",
                                                                                accountManagerOnDutyEmail));

        log.trace("Account Manager's Id={}", accountManager.getId());

        Map<String, Object> contactAttributes = new HashMap<>();
        contactAttributes.put("owner_id", accountManager.getId());
        Contact updatedContact = baseClient.contacts()
                                        .update(dealsContact.getId(), contactAttributes);

        log.debug("Updated contact={}", updatedContact);

        return true;
    }

    private Optional<User> getUserByEmail(final String email) {
        return baseClient.users()
                    .list(new UsersService.SearchCriteria().email(email))
                    .stream()
                    .findFirst();
    }

    private boolean isContactOwnerAnAccountManager(final User user) {
        return accountManagersEmails.contains(user.getEmail());
    }

    private Contact fetchExistingContact(final Long contactId) {
        return baseClient.contacts()
                .get(contactId);
    }

    private boolean isDealStageWon(final Deal deal) {
        return baseClient.stages()
                .list(new StagesService.SearchCriteria().active(false))
                .stream()
                .anyMatch(s -> s.getCategory().contentEquals("won") && deal.getStageId() == s.getId());
    }

    @SuppressWarnings("squid:S1192")
    private void createNewDeal(final Contact newContact) {
        MDC.put("contactId", newContact.getId().toString());
        log.info("Creating new deal");

        DateTimeFormatter selectedFormatter;
        try {
            selectedFormatter = DateTimeFormatter.ofPattern(dealNameDateFormat);
        } catch (IllegalArgumentException e) {
            log.error("Illegal date format. Property workflow.deal.name.date.format={} Message={}",
                        dealNameDateFormat,
                        e.getMessage(),
                        e);
            log.error("Setting default to ISO local date format: yyyy-MM-dd");

            selectedFormatter = DateTimeFormatter.ISO_LOCAL_DATE;
        }

        String dateSuffix = ZonedDateTime.now()
                .toLocalDate()
                .format(selectedFormatter);

        String dealName = newContact.getName() + " " + dateSuffix;

        Deal newDeal = new Deal();
        newDeal.setName(dealName);
        newDeal.setContactId(newContact.getId());
        newDeal.setOwnerId(newContact.getOwnerId());

        Deal newlyCreatedDeal = baseClient.deals()
                                            .create(newDeal);

        MDC.clear();
        log.debug("Created new deal={}", newlyCreatedDeal);
    }

    private boolean shouldNewDealBeCreated(final Contact contact) {
        boolean isContactACompany = contact.getIsOrganization();
        log.trace("Is current contact a company={}", isContactACompany);

        long ownerId = contact.getOwnerId();
        User owner = fetchOwner(ownerId);
        log.trace("Contact's owner={}", owner);

        long contactId = contact.getId();
        log.trace("Contact's id={}", contactId);

        boolean isUserSalesRepresentative = salesRepresentativesEmails.contains(owner.getEmail());
        log.trace("Is contact's owner a sales representative={}", isUserSalesRepresentative);

        boolean activeDealsMissing = areNoActiveDealsFound(contactId);
        log.trace("No deals found={}", activeDealsMissing);

        boolean result = isContactACompany && isUserSalesRepresentative && activeDealsMissing;
        log.debug("Should new deal be created={}", result);

        return result;
    }

    private boolean areNoActiveDealsFound(final Long contactId) {
        List<Long> activeStageIds = baseClient.stages()
                                                        .list(new StagesService.SearchCriteria().active(true))
                                                        .stream()
                                                        .map(Stage::getId)
                                                        .collect(toList());

        return baseClient.deals()
                            .list(new DealsService.SearchCriteria().contactId(contactId))
                            .stream()
                            .noneMatch(d -> activeStageIds.contains(d.getStageId()));
    }

    private User fetchOwner(final long userId) {
        return baseClient.users()
                    .get(userId);
    }

    private String getAccessToken() {
        return Optional.ofNullable(System.getProperty("BASE_CRM_TOKEN", System.getenv("BASE_CRM_TOKEN"))).
                orElseThrow(() -> new IllegalStateException("Missing Base CRM OAuth2 token"));
    }

    private String getDeviceUuid() {
        return Optional.ofNullable(System.getProperty("DEVICE_UUID", System.getenv("DEVICE_UUID"))).
                orElseThrow(() -> new IllegalStateException("Missing Base CRM device uuid"));
    }

    private List<String> getEmailsOfSalesRepresentatives() {
        String emails = System.getProperty("workflow.sales.representatives.emails",
                                System.getenv("workflow_sales_representatives_emails"));
        log.trace("Sales representatives emails (raw)={}", emails);

        return Arrays.stream(Optional.ofNullable(emails)
                        .orElseThrow(() -> new IllegalStateException("Empty list of sales representatives' emails"))
                        .split(","))
                        .map(String::trim)
                        .collect(toList());
    }

    private List<String> getEmailsOfAccountManagers() {
        String emails = System.getProperty("workflow.account.managers.emails",
                                System.getenv("workflow_account_managers_emails"));
        log.trace("Account managers emails (raw)={}", emails);

        return Arrays.stream(Optional.ofNullable(emails)
                        .orElseThrow(() -> new IllegalStateException("Empty list of account managers emails"))
                        .split(","))
                        .map(String::trim)
                        .collect(toList());
    }

    private String getAccountManagerOnDuty() {
        String email = System.getProperty("workflow.account.manager.on.duty.email",
                                System.getenv("workflow_account_manager_on_duty_email"));
        log.trace("Account manager on duty email={}", email);

        return Optional.ofNullable(email)
                .orElseThrow(() -> new IllegalStateException("Empty email of the manager on duty"))
                .trim();
    }
}
