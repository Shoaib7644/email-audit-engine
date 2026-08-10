package com.acxiom.emailaudit.gmail;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.URLName;
import javax.mail.internet.MimeMessage;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.SearchTerm;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class GmailSearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Session SESSION = Session.getInstance(new Properties());

    @DataProvider(name = "safeSubjects")
    public Object[][] safeSubjects() {
        return new Object[][] {
                {"Normal campaign subject"},
                {"Subject with \"quoted\" text"},
                {"Subject with (parentheses)"},
                {"Subject with [brackets]"},
                {"Subject with apostrophe's value"},
                {"Unicode subject – Café 測試"},
                {"Subject   with   multiple   spaces"},
                {"Subject with A&B offer"},
                {"Subject: with colon"}
        };
    }

    @Test(dataProvider = "safeSubjects")
    public void findNewestMatchesArbitrarySubjectTextInJava(final String subject)
            throws Exception {

        final TestMessage older = message(
                "older@example.test",
                subject,
                NOW.minus(Duration.ofHours(2)));
        final TestMessage newer = message(
                "newer@example.test",
                subject,
                NOW.minus(Duration.ofMinutes(10)));
        final CapturingFolder folder = new CapturingFolder(older, newer);
        final GmailSearchService service = new GmailSearchService(CLOCK);

        try (GmailSearchService.SearchResult result =
                     service.findNewest(new FakeStore(folder), "Inbox", subject, "", null)) {

            assertEquals(((MimeMessage) result.message()).getMessageID(), "<newer@example.test>");
            assertEquals(folder.searchCalls, 0, "Subject text should not be sent as an IMAP SEARCH term.");
            assertNull(folder.lastSearchTerm);
        }
    }

    @Test
    public void findNewestNormalizesWhitespaceDuringLocalSubjectMatch()
            throws Exception {

        final CapturingFolder folder = new CapturingFolder(message(
                "id@example.test",
                "Subject with multiple spaces",
                NOW.minus(Duration.ofMinutes(5))));
        final GmailSearchService service = new GmailSearchService(CLOCK);

        try (GmailSearchService.SearchResult result =
                     service.findNewest(
                             new FakeStore(folder),
                             "Inbox",
                             "Subject   with   multiple   spaces",
                             "",
                             null)) {

            assertEquals(((MimeMessage) result.message()).getMessageID(), "<id@example.test>");
        }
    }

    @Test
    public void findNewestFiltersBySubjectAndMessageIdInJava()
            throws Exception {

        final String subject = "Campaign: Launch [QA]";
        final TestMessage requested = message(
                "requested@example.test",
                subject,
                NOW.minus(Duration.ofHours(1)));
        final TestMessage newerWrongId = message(
                "other@example.test",
                subject,
                NOW.minus(Duration.ofMinutes(1)));
        final CapturingFolder folder = new CapturingFolder(requested, newerWrongId);
        final GmailSearchService service = new GmailSearchService(CLOCK);

        try (GmailSearchService.SearchResult result =
                     service.findNewest(
                             new FakeStore(folder),
                             "Inbox",
                             subject,
                             "requested@example.test",
                             null)) {

            assertEquals(((MimeMessage) result.message()).getMessageID(), "<requested@example.test>");
            assertEquals(folder.searchCalls, 0, "Message-ID should be applied after candidate retrieval.");
        }
    }

    @Test
    public void findNewestUsesOnlyReceivedDateAsServerSidePrefilter()
            throws Exception {

        final String subject = "Subject with \"quotes\" and (parentheses)";
        final CapturingFolder folder = new CapturingFolder(message(
                "id@example.test",
                subject,
                NOW.minus(Duration.ofMinutes(30))));
        final GmailSearchService service = new GmailSearchService(CLOCK);

        try (GmailSearchService.SearchResult result =
                     service.findNewest(
                             new FakeStore(folder),
                             "Inbox",
                             subject,
                             "",
                             Duration.ofHours(24))) {

            assertEquals(((MimeMessage) result.message()).getMessageID(), "<id@example.test>");
            assertEquals(folder.searchCalls, 1);
            assertTrue(folder.lastSearchTerm instanceof ReceivedDateTerm);
        }
    }

    @Test
    public void findNewestThrowsWhenNoMessageMatches()
            throws Exception {

        final CapturingFolder folder = new CapturingFolder(message(
                "id@example.test",
                "Different subject",
                NOW.minus(Duration.ofMinutes(30))));
        final GmailSearchService service = new GmailSearchService(CLOCK);

        assertThrows(
                GmailException.class,
                () -> service.findNewest(
                        new FakeStore(folder),
                        "Inbox",
                        "Requested subject",
                        "",
                        null));
    }

    private static TestMessage message(
            final String messageId,
            final String subject,
            final Instant receivedAt) throws MessagingException {

        return new TestMessage(messageId, subject, receivedAt);
    }

    private static final class TestMessage extends MimeMessage {
        private final Date receivedDate;

        private TestMessage(
                final String messageId,
                final String subject,
                final Instant receivedAt) throws MessagingException {

            super(SESSION);
            this.receivedDate = Date.from(receivedAt);
            setSubject(subject, "UTF-8");
            setSentDate(this.receivedDate);
            setHeader("Message-ID", "<" + messageId + ">");
        }

        @Override
        public Date getReceivedDate() {
            return receivedDate;
        }
    }

    private static final class FakeStore extends Store {
        private final CapturingFolder folder;

        private FakeStore(final CapturingFolder folder) {
            super(SESSION, null);
            this.folder = folder;
        }

        @Override
        public Folder getDefaultFolder() {
            return folder;
        }

        @Override
        public Folder getFolder(final String name) {
            return folder;
        }

        @Override
        public Folder getFolder(final URLName url) {
            return folder;
        }
    }

    private static final class CapturingFolder extends Folder {
        private final Message[] messages;
        private boolean open;
        private SearchTerm lastSearchTerm;
        private int searchCalls;

        private CapturingFolder(final Message... messages) {
            super(new Store(SESSION, null) {
                @Override
                public Folder getDefaultFolder() {
                    return null;
                }

                @Override
                public Folder getFolder(final String name) {
                    return null;
                }

                @Override
                public Folder getFolder(final URLName url) {
                    return null;
                }
            });
            this.messages = messages;
        }

        @Override
        public String getName() {
            return "INBOX";
        }

        @Override
        public String getFullName() {
            return "INBOX";
        }

        @Override
        public Folder getParent() {
            return null;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public Folder[] list(final String pattern) {
            return new Folder[0];
        }

        @Override
        public char getSeparator() {
            return '/';
        }

        @Override
        public int getType() {
            return HOLDS_MESSAGES;
        }

        @Override
        public boolean create(final int type) {
            return false;
        }

        @Override
        public boolean hasNewMessages() {
            return false;
        }

        @Override
        public Folder getFolder(final String name) {
            return this;
        }

        @Override
        public boolean delete(final boolean recurse) {
            return false;
        }

        @Override
        public boolean renameTo(final Folder folder) {
            return false;
        }

        @Override
        public void open(final int mode) {
            open = true;
        }

        @Override
        public void close(final boolean expunge) {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public Flags getPermanentFlags() {
            return new Flags();
        }

        @Override
        public int getMessageCount() {
            return messages.length;
        }

        @Override
        public Message getMessage(final int msgnum) {
            return messages[msgnum - 1];
        }

        @Override
        public Message[] search(final SearchTerm term) {
            lastSearchTerm = term;
            searchCalls++;
            return Arrays.stream(messages)
                    .filter(term::match)
                    .toArray(Message[]::new);
        }

        @Override
        public void appendMessages(final Message[] messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Message[] expunge() {
            return new Message[0];
        }
    }
}
