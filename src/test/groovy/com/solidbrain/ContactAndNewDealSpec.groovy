package com.solidbrain

import com.getbase.Client
import com.getbase.models.Contact
import com.getbase.Configuration
import com.getbase.models.Deal
import com.getbase.services.StagesService
import com.getbase.services.UsersService
import groovy.util.logging.Slf4j
import spock.lang.Shared
import spock.lang.Specification

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.SECONDS
import static org.awaitility.Awaitility.await


/**
 * Created by Krzysztof Wilk on 05/09/16.
 */

@Slf4j
class ContactAndNewDealSpec extends Specification {
    Client baseClient = new Client(new Configuration.Builder()
                                                        .accessToken(accessToken)
                                                        .build())

    def sampleSalesRepId = getSampleSalesRep()?.id
    def sampleAccountManagerId = getAccountManagerOnDuty()?.id

    @Shared def dealNameDateFormat

    long waitForWorkflowExecutionTimeout = 30_000
    long awaitPollingInterval = 1_000
    long postDeleteTimeout = 60_000

    def getAccessToken() {
        def token = System.getProperty("BASE_CRM_TOKEN", System.getenv("BASE_CRM_TOKEN"))
        assert token
        return token
    }

    def getSampleSalesRep() {
        def emails = System.getProperty("workflow.sales.representatives.emails")
        assert emails

        def email = emails.split(",")[0]

        def salesRep = baseClient.users().list(new UsersService.SearchCriteria().email(email))[0]
        assert salesRep
        return salesRep
    }

    def getAccountManagerOnDuty() {
        def email = System.getProperty("workflow.account.manager.on.duty.email")
        assert email

        def accountManager = baseClient.users().list(new UsersService.SearchCriteria().email(email))[0]
        assert accountManager
        return accountManager
    }

    def setupSpec() {
        def file = new File("src/test/resources/test.properties")

        file.eachLine { l -> if (l.startsWith("workflow.deal.name.date.format")) {
                                    dealNameDateFormat = l.split("=")[1]
                                }
        }

        assert dealNameDateFormat
    }

    def cleanup() {
        def sampleCompanyId = baseClient.contacts().list([name : sampleCompanyName])[0]?.id

        if (sampleCompanyId) {
            def dealIds = baseClient.deals().list([contact_id : sampleCompanyId])*.id
            if (dealIds) {
                dealIds.each { id -> baseClient.deals().delete(id) }
                await().atMost(postDeleteTimeout, MILLISECONDS).pollInterval(1, SECONDS).until {
                    baseClient.deals().list([contact_id : sampleCompanyId]).isEmpty()
                }
            }

            baseClient.contacts().delete(sampleCompanyId)
            await().atMost(postDeleteTimeout, MILLISECONDS).pollInterval(1, SECONDS).until {
                baseClient.contacts().list([name : sampleCompanyName]).isEmpty()
            }
        }
    }


    def getSampleCompanyName() {
        "Some Company Of Mine"
    }

    def getSampleDealName(String contactName) {
        contactName + " " + ZonedDateTime.now().toLocalDate().format(DateTimeFormatter.ofPattern(dealNameDateFormat))
    }

    def "should create deal if the newly created contact is a company and the owner of the newly created contact is a sales representative"() {
        when: "new contact that is a company owned by a sales rep is created"
        Contact sampleContact = baseClient.contacts().create([name : sampleCompanyName,
                                                              is_organization:  true,
                                                              owner_id: sampleSalesRepId])

        then: "new deal at the first stage of the pipeline for this company is created"
        await().atMost(waitForWorkflowExecutionTimeout, MILLISECONDS).pollInterval(awaitPollingInterval, MILLISECONDS).until {
            !baseClient.deals().list([contact_id: sampleContact.id]).isEmpty()
        }
        Deal sampleDeal = baseClient.deals().list([contact_id: sampleContact.id]).get(0)
        sampleDeal.name == getSampleDealName(sampleCompanyName)
        sampleDeal.ownerId == sampleContact.ownerId
        sampleDeal.stageId == getFirstStage(sampleDeal)?.id
    }

    def getFirstStage(Deal deal) {
        baseClient.stages()
                .list(new StagesService.SearchCriteria()
                                            .active(true))
                .stream()
                .filter { s -> s.position == 1 && deal.stageId == s.id }
                .findFirst()
                .get()
    }

    def "should not create deal if the newly created contact is not a company"() {
        when: "new contact that is not a company is created"
        Contact sampleContact = baseClient.contacts().create([name : sampleCompanyName,
                                                              is_organization:  false])

        then: "no new deal is created"
        sleep(waitForWorkflowExecutionTimeout)
        !baseClient.deals().list([contact_id: sampleContact.id])
    }

    def "should not create deal if the owner of the newly created contact is not a sales representative"() {
        when: "new contact that is a company that is not owned by a sales rep is created"
        Contact sampleContact = baseClient.contacts().create([name : sampleCompanyName,
                                                              is_organization:  true,
                                                              owner_id: sampleAccountManagerId])

        then: "no new deal is created"
        sleep(waitForWorkflowExecutionTimeout)
        !baseClient.deals().list([contact_id: sampleContact.id])
    }
}
