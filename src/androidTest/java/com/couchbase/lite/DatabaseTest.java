package com.couchbase.lite;

import com.couchbase.lite.internal.RevisionInternal;
import com.couchbase.lite.mockserver.MockDispatcher;
import com.couchbase.lite.mockserver.MockHelper;
import com.couchbase.lite.replicator.Replication;
import com.couchbase.lite.support.FileDirUtils;
import com.couchbase.lite.support.RevisionUtils;
import com.couchbase.lite.util.Log;
import com.couchbase.lite.util.TextUtils;
import com.squareup.okhttp.mockwebserver.MockWebServer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DatabaseTest extends LiteTestCaseWithDB {
    /**
     * in DatabaseInternal_Tests.m
     * - (void) test27_ChangesSinceSequence
     */
    public void test27_ChangesSinceSequence() throws CouchbaseLiteException {
        // Create 10 docs:
        createDocuments(database, 10);

        // Create a new doc with a conflict:
        RevisionInternal rev = new RevisionInternal("MyDocID", "1-1111", false);
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("_id", rev.getDocID());
        properties.put("_rev", rev.getRevID());
        properties.put("message", "hi");
        rev.setProperties(properties);
        List<String> history = Arrays.asList(rev.getRevID());
        database.forceInsert(rev, history, null);
        rev = new RevisionInternal("MyDocID", "1-ffff", false);
        properties = new HashMap<String, Object>();
        properties.put("_id", rev.getDocID());
        properties.put("_rev", rev.getRevID());
        properties.put("message", "bye");
        rev.setProperties(properties);
        history = Arrays.asList(rev.getRevID());
        database.forceInsert(rev, history, null);

        // Create another doc with a merged conflict:
        rev = new RevisionInternal("MyDocID2", "1-1111", false);
        properties = new HashMap<String, Object>();
        properties.put("_id", rev.getDocID());
        properties.put("_rev", rev.getRevID());
        properties.put("message", "hi");
        rev.setProperties(properties);
        history = Arrays.asList(rev.getRevID());
        database.forceInsert(rev, history, null);
        rev = new RevisionInternal("MyDocID2", "1-ffff", true);
        properties = new HashMap<String, Object>();
        properties.put("_id", rev.getDocID());
        properties.put("_rev", rev.getRevID());
        rev.setProperties(properties);
        history = Arrays.asList(rev.getRevID());
        database.forceInsert(rev, history, null);

        // Get changes, testing all combinations of includeConflicts and includeDocs:
        for (int conflicts = 0; conflicts <= 1; conflicts++) {
            for (int bodies = 0; bodies <= 1; bodies++) {
                ChangesOptions options = new ChangesOptions();
                options.setIncludeConflicts(conflicts != 0);
                options.setIncludeDocs(bodies != 0);
                RevisionList changes = database.changesSince(0, options, null, null);
                assertEquals(12 + 2 * conflicts, changes.size());
                for (RevisionInternal change : changes) {
                    if (bodies != 0)
                        assertNotNull(change.getBody());
                    else
                        assertNull(change.getBody());
                }
            }
        }
    }

    public void testPruneRevsToMaxDepthViaCompact() throws Exception {

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("testName", "testDatabaseCompaction");
        properties.put("tag", 1337);

        Document doc = createDocumentWithProperties(database, properties);
        SavedRevision rev = doc.getCurrentRevision();

        database.setMaxRevTreeDepth(2);
        for (int i = 0; i < 10; i++) {
            Map<String, Object> properties2 = new HashMap<String, Object>(properties);
            properties2.put("tag", i);
            rev = rev.createRevision(properties2);
        }

        database.compact();

        Document fetchedDoc = database.getDocument(doc.getId());
        List<SavedRevision> revisions = fetchedDoc.getRevisionHistory();
        assertEquals(2, revisions.size());
    }

    /**
     * When making inserts in a transaction, the change notifications should
     * be batched into a single change notification (rather than a change notification
     * for each insert)
     */
    public void testChangeListenerNotificationBatching() throws Exception {

        final int numDocs = 50;
        final AtomicInteger atomicInteger = new AtomicInteger(0);
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        database.addChangeListener(new Database.ChangeListener() {
            @Override
            public void changed(Database.ChangeEvent event) {
                atomicInteger.incrementAndGet();
            }
        });

        database.runInTransaction(new TransactionalTask() {
            @Override
            public boolean run() {
                createDocuments(database, numDocs);
                countDownLatch.countDown();
                return true;
            }
        });

        boolean success = countDownLatch.await(30, TimeUnit.SECONDS);
        assertTrue(success);

        assertEquals(1, atomicInteger.get());
    }

    /**
     * When making inserts outside of a transaction, there should be a change notification
     * for each insert (no batching)
     */
    public void testChangeListenerNotification() throws Exception {
        final int numDocs = 50;
        final AtomicInteger atomicInteger = new AtomicInteger(0);
        database.addChangeListener(new Database.ChangeListener() {
            @Override
            public void changed(Database.ChangeEvent event) {
                atomicInteger.incrementAndGet();
            }
        });
        createDocuments(database, numDocs);
        assertEquals(numDocs, atomicInteger.get());
    }

    /**
     * Change listeners should only be called once no matter how many times they're added.
     */
    public void testAddChangeListenerIsIdempotent() throws Exception {
        final AtomicInteger count = new AtomicInteger(0);
        Database.ChangeListener listener = new Database.ChangeListener() {
            @Override
            public void changed(Database.ChangeEvent event) {
                count.incrementAndGet();
            }
        };
        database.addChangeListener(listener);
        database.addChangeListener(listener);
        createDocuments(database, 1);
        assertEquals(1, count.intValue());
    }

    public void testGetActiveReplications() throws Exception {

        // create mock sync gateway that will serve as a pull target and return random docs
        int numMockDocsToServe = 0;
        MockDispatcher dispatcher = new MockDispatcher();
        MockWebServer server = MockHelper.getPreloadedPullTargetMockCouchDB(dispatcher, numMockDocsToServe, 1);
        dispatcher.setServerType(MockDispatcher.ServerType.COUCHDB);
        server.setDispatcher(dispatcher);
        try {
            server.start();

            final Replication replication = database.createPullReplication(server.getUrl("/db"));

            assertEquals(0, database.getAllReplications().size());
            assertEquals(0, database.getActiveReplications().size());

            final CountDownLatch replicationRunning = new CountDownLatch(1);
            replication.addChangeListener(new ReplicationActiveObserver(replicationRunning));

            replication.start();

            boolean success = replicationRunning.await(30, TimeUnit.SECONDS);
            assertTrue(success);

            assertEquals(1, database.getAllReplications().size());
            assertEquals(1, database.getActiveReplications().size());

            final CountDownLatch replicationDoneSignal = new CountDownLatch(1);
            replication.addChangeListener(new ReplicationFinishedObserver(replicationDoneSignal));

            success = replicationDoneSignal.await(60, TimeUnit.SECONDS);
            assertTrue(success);

            // workaround race condition.  Since our replication change listener will get triggered
            // _before_ the internal change listener that updates the activeReplications map, we
            // need to pause briefly to let the internal change listener to update activeReplications.
            Thread.sleep(500);

            assertEquals(1, database.getAllReplications().size());
            assertEquals(0, database.getActiveReplications().size());
        }finally {
            server.shutdown();
        }
    }

    public void testGetDatabaseNameFromPath() throws Exception {

        assertEquals("baz", FileDirUtils.getDatabaseNameFromPath("foo/bar/baz.cblite"));

    }

    public void testEncodeDocumentJSON() throws Exception {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("_local_seq", "");
        RevisionInternal revisionInternal = new RevisionInternal(props);
        byte[] encoded = RevisionUtils.asCanonicalJSON(revisionInternal);
        assertNotNull(encoded);
    }

    /**
     * in Database_Tests.m
     * - (void) test075_UpdateDocInTransaction
     */
    public void testUpdateDocInTransaction() throws InterruptedException {
        // Test for #256, "Conflict error when updating a document multiple times in transaction block"
        // https://github.com/couchbase/couchbase-lite-ios/issues/256

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("testName", "testUpdateDocInTransaction");
        properties.put("count", 1);

        final Document doc = createDocumentWithProperties(database, properties);

        final CountDownLatch latch = new CountDownLatch(1);
        database.addChangeListener(new Database.ChangeListener() {
            @Override
            public void changed(Database.ChangeEvent event) {
                Log.i(TAG, "-- changed() --");
                latch.countDown();
            }
        });
        assertTrue(database.runInTransaction(new TransactionalTask() {
            @Override
            public boolean run() {
                // Update doc. The currentRevision should update, but no notification be posted (yet).
                Map<String, Object> props1 = new HashMap<String, Object>();
                props1.putAll(doc.getProperties());
                props1.put("count", 2);
                SavedRevision rev1 = null;
                try {
                    rev1 = doc.putProperties(props1);
                } catch (CouchbaseLiteException e) {
                    Log.e(Log.TAG_DATABASE, e.toString());
                    return false;
                }
                assertNotNull(rev1);
                assertEquals(doc.getCurrentRevision(), rev1);
                assertEquals(1, latch.getCount());

                // Update doc again; this should succeed, in the same manner.
                Map<String, Object> props2 = new HashMap<String, Object>();
                props2.putAll(doc.getProperties());
                props2.put("count", 3);
                SavedRevision rev2 = null;
                try {
                    rev2 = doc.putProperties(props2);
                } catch (CouchbaseLiteException e) {
                    Log.e(Log.TAG_DATABASE, e.toString());
                    return false;
                }
                assertNotNull(rev2);
                assertEquals(doc.getCurrentRevision(), rev2);
                assertEquals(1, latch.getCount());

                return true;
            }
        }));
        assertTrue(latch.await(0, TimeUnit.SECONDS));
    }

    public void testClose() throws Exception {
        // Get the database:
        Database db = manager.getDatabase(DEFAULT_TEST_DB);
        assertNotNull(db);
        // Test that the database is remembered by the manager:
        assertEquals(db, manager.getDatabase(DEFAULT_TEST_DB));
        assertTrue(manager.allOpenDatabases().contains(db));

        // Create a new document:
        Document doc = db.getDocument("doc1");
        assertNotNull(doc.putProperties(new HashMap<String, Object>()));
        // Test that the document is remebered by the database:
        assertEquals(doc, db.getCachedDocument("doc1"));

        // Close database:
        database.close();
        // The cache should be clear:
        assertNull(db.getCachedDocument("doc1"));
        // This that the database is forgotten:
        assertFalse(manager.allOpenDatabases().contains(db));
    }

    public void testAndroid2MLimit() throws Exception {
        char[] chars = new char[3 * 1024 * 1024];
        Arrays.fill(chars, 'a');
        final String content = new String(chars);

        // Add a 2M+ document into the database:
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("content", content);
        Document doc = database.createDocument();
        assertNotNull(doc.putProperties(props));
        String docId = doc.getId();

        // Close and reopen the database:
        database.close();
        database = manager.getDatabase(DEFAULT_TEST_DB);

        // Try to read the document:
        doc = database.getDocument(docId);
        assertNotNull(doc);
        Map<String,Object> properties = doc.getProperties();
        assertNotNull(properties);
        assertEquals(content, properties.get("content"));
    }

    // Database_Tests.m : test18_Attachments
    public void testAttachments() throws Exception {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("testName", "testAttachments");
        properties.put("count", 1);

        final Document doc = createDocumentWithProperties(database, properties);
        SavedRevision rev = doc.getCurrentRevision();
        assertEquals(0, rev.getAttachments().size());
        assertEquals(0, rev.getAttachmentNames().size());
        assertNull(rev.getAttachment("index.html"));

        String content = "This is a test attachments!";
        ByteArrayInputStream body = new ByteArrayInputStream(content.getBytes());
        UnsavedRevision rev2 = doc.createRevision();
        rev2.setAttachment("index.html", "text/plain; charset=utf-8", body);

        assertEquals(1, rev2.getAttachments().size());
        assertEquals(1, rev2.getAttachmentNames().size());
        assertEquals("index.html", rev2.getAttachmentNames().get(0));
        Attachment attach = rev2.getAttachment("index.html");
        assertNotNull(attach);
        assertNull(attach.getRevision()); // No revision set
        assertNull(attach.getDocument()); // No revision set
        assertEquals("index.html", attach.getName());
        assertEquals("text/plain; charset=utf-8", attach.getContentType());

        SavedRevision rev3 = rev2.save();
        assertNotNull(rev3);
        assertEquals(1, rev3.getAttachments().size());
        assertEquals(1, rev3.getAttachmentNames().size());
        assertEquals("index.html", rev3.getAttachmentNames().get(0));

        attach = rev3.getAttachment("index.html");
        assertNotNull(attach);
        assertNotNull(attach.getRevision());
        assertNotNull(attach.getDocument());
        assertEquals(doc, attach.getDocument());
        assertEquals("index.html", attach.getName());
        assertEquals("text/plain; charset=utf-8", attach.getContentType());
        assertTrue(Arrays.equals(content.getBytes(), TextUtils.read(attach.getContent())));

        // Look at the attachment's file:
        URL bodyURL = attach.getContentURL();
        if (isEncryptedAttachmentStore()) {
            assertNull(bodyURL);
        } else {
            assertNotNull(bodyURL);
            assertTrue(Arrays.equals(content.getBytes(),
                    TextUtils.read(new File(bodyURL.toURI())).getBytes()));
        }

        UnsavedRevision newRev = rev3.createRevision();
        newRev.removeAttachment(attach.getName());
        SavedRevision rev4 = newRev.save();
        assertNotNull(rev4);
        assertEquals(0, rev4.getAttachments().size());
        assertEquals(0, rev4.getAttachmentNames().size());
    }

    public void testAttachmentsWithEncryption() throws Exception {
        setEncryptedAttachmentStore(true);
        try {
            testAttachments();
        } finally {
            setEncryptedAttachmentStore(false);
        }
    }

    // https://github.com/couchbase/couchbase-lite-android/issues/783
    public void testNonStringForTypeField() throws CouchbaseLiteException {
        // Non String as type
        List<Integer> type1 = new ArrayList();
        type1.add(0);
        type1.add(1);
        Map<String, Object> props1 = new HashMap<String, Object>();
        props1.put("key", "value");
        props1.put("type", type1);
        Document doc1 = database.createDocument();
        doc1.putProperties(props1);

        //  String as type
        String type2 = "STRING";
        Map<String, Object> props2 = new HashMap<String, Object>();
        props1.put("key", "value");
        props1.put("type", type2);
        Document doc2 = database.createDocument();
        doc2.putProperties(props1);
    }
}
