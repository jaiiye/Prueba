/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
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

package com.ning.billing.util.tag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.Seconds;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.tweak.HandleCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import com.google.inject.Inject;
import com.ning.billing.dbi.MysqlTestingHelper;
import com.ning.billing.util.api.TagApiException;
import com.ning.billing.util.api.TagDefinitionApiException;
import com.ning.billing.util.bus.Bus;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.callcontext.CallOrigin;
import com.ning.billing.util.callcontext.DefaultCallContextFactory;
import com.ning.billing.util.callcontext.UserType;
import com.ning.billing.util.clock.Clock;
import com.ning.billing.util.dao.ObjectType;
import com.ning.billing.util.io.IOUtils;
import com.ning.billing.util.tag.dao.TagDao;
import com.ning.billing.util.tag.dao.TagDefinitionDao;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(groups = {"slow"})
@Guice(modules = MockTagStoreModuleSql.class)
public class TestTagStore {
    @Inject
    private MysqlTestingHelper helper;

    @Inject
    private IDBI dbi;

    @Inject
    private TagDao tagDao;

    @Inject
    private TagDefinitionDao tagDefinitionDao;

    @Inject
    private Clock clock;

    @Inject
    private Bus bus;

    private TagDefinition testTag;

    private final Logger log = LoggerFactory.getLogger(TestTagStore.class);
    private CallContext context;

    @BeforeClass(groups = "slow")
    protected void setup() throws IOException {
        // Health check test to make sure MySQL is setup properly
        try {
            final String utilDdl = IOUtils.toString(TestTagStore.class.getResourceAsStream("/com/ning/billing/util/ddl.sql"));

            helper.startMysql();
            helper.initDb(utilDdl);

            context = new DefaultCallContextFactory(clock).createCallContext("Tag store test", CallOrigin.TEST, UserType.TEST);
            bus.start();

            cleanupTags();
            tagDefinitionDao.create("tag1", "First tag", context);
            testTag = tagDefinitionDao.create("testTag", "Second tag", context);
        } catch (Throwable t) {
            log.error("Failed to start tag store tests", t);
            fail(t.toString());
        }
    }

    @AfterClass(groups = "slow")
    public void stopMysql() {
        bus.stop();
        if (helper != null) {
            helper.stopMysql();
        }
    }

    private void cleanupTags() {
        try {
            helper.getDBI().withHandle(new HandleCallback<Void>() {
                @Override
                public Void withHandle(final Handle handle) throws Exception {
                    handle.createScript("delete from tag_definitions").execute();
                    handle.createScript("delete from tag_definition_history").execute();
                    handle.createScript("delete from tags").execute();
                    handle.createScript("delete from tag_history").execute();
                    return null;
                }
            });
        } catch (Throwable ignore) {
        }
    }

    @Test(groups = "slow")
    public void testTagCreationAndRetrieval() {
        final UUID accountId = UUID.randomUUID();

        final TagStore tagStore = new DefaultTagStore(accountId, ObjectType.ACCOUNT);
        final Tag tag = new DescriptiveTag(testTag);
        tagStore.add(tag);

        tagDao.saveEntities(accountId, ObjectType.ACCOUNT, tagStore.getEntityList(), context);

        final Map<String, Tag> savedTags = tagDao.loadEntities(accountId, ObjectType.ACCOUNT);
        assertEquals(savedTags.size(), 1);

        final Tag savedTag = savedTags.get(tag.getTagDefinitionName());
        assertEquals(savedTag.getTagDefinitionName(), tag.getTagDefinitionName());
        assertEquals(savedTag.getId(), tag.getId());
    }


    @Test(groups = "slow")
    public void testControlTagCreation() {
        final UUID accountId = UUID.randomUUID();
        final TagStore tagStore = new DefaultTagStore(accountId, ObjectType.ACCOUNT);

        final ControlTag tag = new DefaultControlTag(ControlTagType.AUTO_INVOICING_OFF);
        tagStore.add(tag);
        assertEquals(tagStore.generateInvoice(), false);

        final List<Tag> tagList = tagStore.getEntityList();
        tagDao.saveEntities(accountId, ObjectType.ACCOUNT, tagList, context);

        tagStore.clear();
        assertEquals(tagStore.getEntityList().size(), 0);

        final Map<String, Tag> tagMap = tagDao.loadEntities(accountId, ObjectType.ACCOUNT);
        assertEquals(tagMap.size(), 1);

        assertEquals(tagMap.containsKey(ControlTagType.AUTO_INVOICING_OFF.toString()), true);
    }

    @Test(groups = "slow")
    public void testDescriptiveTagCreation() {
        final UUID accountId = UUID.randomUUID();
        final TagStore tagStore = new DefaultTagStore(accountId, ObjectType.ACCOUNT);

        final String definitionName = "SomeTestTag";
        TagDefinition tagDefinition = null;
        try {
            tagDefinition = tagDefinitionDao.create(definitionName, "Test tag for some test purpose", context);
        } catch (TagDefinitionApiException e) {
            fail("Tag definition creation failed.", e);
        }

        final DescriptiveTag tag = new DescriptiveTag(tagDefinition);
        tagStore.add(tag);
        assertEquals(tagStore.generateInvoice(), true);

        tagDao.saveEntities(accountId, ObjectType.ACCOUNT, tagStore.getEntityList(), context);

        tagStore.clear();
        assertEquals(tagStore.getEntityList().size(), 0);

        final Map<String, Tag> tagMap = tagDao.loadEntities(accountId, ObjectType.ACCOUNT);
        assertEquals(tagMap.size(), 1);

        assertEquals(tagMap.containsKey(ControlTagType.AUTO_INVOICING_OFF.toString()), false);
    }

    @Test(groups = "slow")
    public void testMixedTagCreation() {
        final UUID accountId = UUID.randomUUID();
        final TagStore tagStore = new DefaultTagStore(accountId, ObjectType.ACCOUNT);

        final String definitionName = "MixedTagTest";
        TagDefinition tagDefinition = null;
        try {
            tagDefinition = tagDefinitionDao.create(definitionName, "Test tag for some test purpose", context);
        } catch (TagDefinitionApiException e) {
            fail("Tag definition creation failed.", e);
        }

        final DescriptiveTag descriptiveTag = new DescriptiveTag(tagDefinition);
        tagStore.add(descriptiveTag);
        assertEquals(tagStore.generateInvoice(), true);

        final ControlTag controlTag = new DefaultControlTag(ControlTagType.AUTO_INVOICING_OFF);
        tagStore.add(controlTag);
        assertEquals(tagStore.generateInvoice(), false);

        tagDao.saveEntities(accountId, ObjectType.ACCOUNT, tagStore.getEntityList(), context);

        tagStore.clear();
        assertEquals(tagStore.getEntityList().size(), 0);

        final Map<String, Tag> tagMap = tagDao.loadEntities(accountId, ObjectType.ACCOUNT);
        assertEquals(tagMap.size(), 2);

        assertEquals(tagMap.containsKey(ControlTagType.AUTO_INVOICING_OFF.toString()), true);
    }

    @Test(groups = "slow")
    public void testControlTags() {
        final UUID accountId = UUID.randomUUID();
        final TagStore tagStore = new DefaultTagStore(accountId, ObjectType.ACCOUNT);
        assertEquals(tagStore.generateInvoice(), true);
        assertEquals(tagStore.processPayment(), true);

        final ControlTag invoiceTag = new DefaultControlTag(ControlTagType.AUTO_INVOICING_OFF);
        tagStore.add(invoiceTag);
        assertEquals(tagStore.generateInvoice(), false);
        assertEquals(tagStore.processPayment(), true);

        final ControlTag paymentTag = new DefaultControlTag(ControlTagType.AUTO_PAY_OFF);
        tagStore.add(paymentTag);
        assertEquals(tagStore.generateInvoice(), false);
        assertEquals(tagStore.processPayment(), false);
    }

    @Test(groups = "slow", expectedExceptions = TagDefinitionApiException.class)
    public void testTagDefinitionCreationWithControlTagName() throws TagDefinitionApiException {
        final String definitionName = ControlTagType.AUTO_PAY_OFF.toString();
        tagDefinitionDao.create(definitionName, "This should break", context);
    }

    @Test(groups = "slow")
    public void testTagDefinitionDeletionForUnusedDefinition() throws TagDefinitionApiException {
        final String definitionName = "TestTag1234";
        tagDefinitionDao.create(definitionName, "Some test tag", context);

        TagDefinition tagDefinition = tagDefinitionDao.getByName(definitionName);
        assertNotNull(tagDefinition);

        tagDefinitionDao.deleteTagDefinition(definitionName, context);
        tagDefinition = tagDefinitionDao.getByName(definitionName);
        assertNull(tagDefinition);
    }

    @Test(groups = "slow", expectedExceptions = TagDefinitionApiException.class)
    public void testTagDefinitionDeletionForDefinitionInUse() throws TagDefinitionApiException {
        final String definitionName = "TestTag12345";
        tagDefinitionDao.create(definitionName, "Some test tag", context);

        final TagDefinition tagDefinition = tagDefinitionDao.getByName(definitionName);
        assertNotNull(tagDefinition);

        final UUID objectId = UUID.randomUUID();
        final TagStore tagStore = new DefaultTagStore(objectId, ObjectType.ACCOUNT);
        final Tag tag = new DescriptiveTag(tagDefinition);
        tagStore.add(tag);

        tagDao.saveEntities(objectId, ObjectType.ACCOUNT, tagStore.getEntityList(), context);

        final Map<String, Tag> tagMap = tagDao.loadEntities(objectId, ObjectType.ACCOUNT);
        assertEquals(tagMap.size(), 1);

        tagDefinitionDao.deleteTagDefinition(definitionName, context);
    }

    @Test(groups = "slow")
    public void testDeleteTagBeforeDeleteTagDefinition() throws TagApiException {
        final String definitionName = "TestTag1234567";
        try {
            tagDefinitionDao.create(definitionName, "Some test tag", context);
        } catch (TagDefinitionApiException e) {
            fail("Could not create tag definition", e);
        }

        final TagDefinition tagDefinition = tagDefinitionDao.getByName(definitionName);
        assertNotNull(tagDefinition);

        final UUID objectId = UUID.randomUUID();
        final TagStore tagStore = new DefaultTagStore(objectId, ObjectType.ACCOUNT);
        final Tag tag = new DescriptiveTag(tagDefinition);
        tagStore.add(tag);

        tagDao.saveEntities(objectId, ObjectType.ACCOUNT, tagStore.getEntityList(), context);

        final Map<String, Tag> tagMap = tagDao.loadEntities(objectId, ObjectType.ACCOUNT);
        assertEquals(tagMap.size(), 1);

        tagDao.deleteTag(objectId, ObjectType.ACCOUNT, tagDefinition, context);
        final Map<String, Tag> tagMapAfterDeletion = tagDao.loadEntities(objectId, ObjectType.ACCOUNT);
        assertEquals(tagMapAfterDeletion.size(), 0);

        try {
            tagDefinitionDao.deleteTagDefinition(definitionName, context);
        } catch (TagDefinitionApiException e) {
            fail("Could not delete tag definition", e);
        }
    }

    @Test(groups = "slow")
    public void testGetTagDefinitions() {
        final List<TagDefinition> definitionList = tagDefinitionDao.getTagDefinitions();
        assertTrue(definitionList.size() >= ControlTagType.values().length);
    }

    @Test
    public void testTagInsertAudit() {
        final UUID accountId = UUID.randomUUID();

        final TagStore tagStore = new DefaultTagStore(accountId, ObjectType.ACCOUNT);
        final Tag tag = new DescriptiveTag(testTag);
        tagStore.add(tag);

        tagDao.saveEntities(accountId, ObjectType.ACCOUNT, tagStore.getEntityList(), context);

        final Map<String, Tag> savedTags = tagDao.loadEntities(accountId, ObjectType.ACCOUNT);
        assertEquals(savedTags.size(), 1);

        final Tag savedTag = savedTags.get(tag.getTagDefinitionName());
        assertEquals(savedTag.getTagDefinitionName(), tag.getTagDefinitionName());
        assertEquals(savedTag.getId(), tag.getId());

        final Handle handle = dbi.open();
        final String query = String.format("select * from audit_log a inner join tag_history th on a.record_id = th.history_record_id where a.table_name = 'tag_history' and th.id='%s' and a.change_type='INSERT'",
                                           tag.getId().toString());
        final List<Map<String, Object>> result = handle.select(query);
        handle.close();

        assertNotNull(result);
        assertEquals(result.size(), 1);
        assertEquals(result.get(0).get("change_type"), "INSERT");
        assertNotNull(result.get(0).get("change_date"));
        final DateTime changeDate = new DateTime(result.get(0).get("change_date"));
        assertTrue(Seconds.secondsBetween(changeDate, context.getCreatedDate()).getSeconds() < 2);
        assertEquals(result.get(0).get("changed_by"), context.getUserName());
    }

    @Test
    public void testTagDeleteAudit() {
        final UUID accountId = UUID.randomUUID();

        final TagStore tagStore = new DefaultTagStore(accountId, ObjectType.ACCOUNT);
        final Tag tag = new DescriptiveTag(testTag);
        tagStore.add(tag);

        tagDao.saveEntities(accountId, ObjectType.ACCOUNT, tagStore.getEntityList(), context);

        tagStore.remove(tag);
        tagDao.saveEntities(accountId, ObjectType.ACCOUNT, tagStore.getEntityList(), context);

        final Map<String, Tag> savedTags = tagDao.loadEntities(accountId, ObjectType.ACCOUNT);
        assertEquals(savedTags.size(), 0);

        final Handle handle = dbi.open();
        final String query = String.format("select * from audit_log a inner join tag_history th on a.record_id = th.history_record_id where a.table_name = 'tag_history' and th.id='%s' and a.change_type='DELETE'",
                                           tag.getId().toString());
        final List<Map<String, Object>> result = handle.select(query);
        handle.close();

        assertNotNull(result);
        assertEquals(result.size(), 1);
        assertNotNull(result.get(0).get("change_date"));
        final DateTime changeDate = new DateTime(result.get(0).get("change_date"));
        assertTrue(Seconds.secondsBetween(changeDate, context.getUpdatedDate()).getSeconds() < 2);
        assertEquals(result.get(0).get("changed_by"), context.getUserName());
    }

    @Test
    public void testAddTag() throws TagApiException {
        final UUID objectId = UUID.randomUUID();
        final ObjectType objectType = ObjectType.INVOICE;
        final TagDefinition tagDefinition = new DefaultTagDefinition("test tag", "test", false);
        tagDao.insertTag(objectId, objectType, tagDefinition, context);
        final Map<String, Tag> savedTags = tagDao.loadEntities(objectId, objectType);
        assertEquals(savedTags.size(), 1);
    }

    @Test
    public void testRemoveTag() throws TagApiException {
        final UUID objectId = UUID.randomUUID();
        final ObjectType objectType = ObjectType.INVOICE;
        final TagDefinition tagDefinition = new DefaultTagDefinition("test tag", "test", false);
        tagDao.insertTag(objectId, objectType, tagDefinition, context);
        Map<String, Tag> savedTags = tagDao.loadEntities(objectId, objectType);
        assertEquals(savedTags.size(), 1);

        tagDao.deleteTag(objectId, objectType, tagDefinition, context);
        savedTags = tagDao.loadEntities(objectId, objectType);
        assertEquals(savedTags.size(), 0);
    }

    @Test
    public void testSetTags() {
        final UUID objectId = UUID.randomUUID();
        final ObjectType objectType = ObjectType.INVOICE;

        final List<Tag> tags = new ArrayList<Tag>();
        tags.add(new DescriptiveTag("test 1"));
        tags.add(new DescriptiveTag("test 2"));
        tags.add(new DefaultControlTag(ControlTagType.AUTO_INVOICING_OFF));
        tagDao.saveEntities(objectId, objectType, tags, context);

        Map<String, Tag> savedTags = tagDao.loadEntities(objectId, objectType);
        assertEquals(savedTags.size(), 3);

        tags.remove(1);
        assertEquals(tags.size(), 2);

        tagDao.saveEntities(objectId, objectType, tags, context);

        savedTags = tagDao.loadEntities(objectId, objectType);
        assertEquals(savedTags.size(), 2);
    }
}
