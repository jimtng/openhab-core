/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.internal.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.core.JavaTest;
import org.openhab.core.service.WatchService;
import org.openhab.core.service.WatchService.Kind;
import org.osgi.framework.BundleContext;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.hashing.FileHash;

/**
 * The {@link WatchServiceImplTest} is a
 *
 * @author Jan N. Klug - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class WatchServiceImplTest extends JavaTest {
    private static final String SUB_DIR_PATH_NAME = "subDir";
    private static final String TEST_FILE_NAME = "testFile";

    public @Mock @NonNullByDefault({}) WatchServiceImpl.WatchServiceConfiguration configurationMock;
    public @Mock @NonNullByDefault({}) BundleContext bundleContextMock;

    private @NonNullByDefault({}) WatchServiceImpl watchService;
    private @NonNullByDefault({}) @TempDir Path rootPath;
    private @NonNullByDefault({}) TestWatchEventListener listener;

    @BeforeEach
    public void setup() throws IOException {
        when(configurationMock.name()).thenReturn("unnamed");
        when(configurationMock.path()).thenReturn(rootPath.toString());

        watchService = new WatchServiceImpl(configurationMock, bundleContextMock);
        listener = new TestWatchEventListener();

        verify(bundleContextMock, timeout(5000)).registerService(eq(WatchService.class), eq(watchService), any());
    }

    @AfterEach
    public void tearDown() throws IOException {
        watchService.deactivate();
    }

    @Test
    public void testFileInWatchedDir() throws IOException, InterruptedException {
        watchService.registerListener(listener, rootPath, false);

        Path testFile = rootPath.resolve(TEST_FILE_NAME);
        Path relativeTestFilePath = Path.of(TEST_FILE_NAME);

        Files.writeString(testFile, "initial content", StandardCharsets.UTF_8);
        assertEvent(relativeTestFilePath, Kind.CREATE);

        Files.writeString(testFile, "modified content", StandardCharsets.UTF_8);
        assertEvent(relativeTestFilePath, Kind.MODIFY);

        Files.writeString(testFile, "modified content", StandardCharsets.UTF_8);
        assertNoEvents();

        Files.delete(testFile);
        assertEvent(relativeTestFilePath, Kind.DELETE);
    }

    @Test
    public void testFileInWatchedSubDir() throws IOException, InterruptedException {
        Files.createDirectories(rootPath.resolve(SUB_DIR_PATH_NAME));

        // listener is listening to root and sub-dir
        watchService.registerListener(listener, rootPath, true);

        Path testFile = rootPath.resolve(SUB_DIR_PATH_NAME).resolve(TEST_FILE_NAME);
        Path relativeTestFilePath = Path.of(SUB_DIR_PATH_NAME, TEST_FILE_NAME);

        Files.writeString(testFile, "initial content", StandardCharsets.UTF_8);
        assertEvent(relativeTestFilePath, Kind.CREATE);

        Files.writeString(testFile, "modified content", StandardCharsets.UTF_8);
        assertEvent(relativeTestFilePath, Kind.MODIFY);

        Files.writeString(testFile, "modified content", StandardCharsets.UTF_8);
        assertNoEvents();

        Files.delete(testFile);
        assertEvent(relativeTestFilePath, Kind.DELETE);
    }

    @Test
    public void testFileInWatchedSubDir2() throws IOException, InterruptedException {
        Files.createDirectories(rootPath.resolve(SUB_DIR_PATH_NAME));

        // listener is only listening to sub-dir of root
        watchService.registerListener(listener, Path.of(SUB_DIR_PATH_NAME), false);

        Path testFile = rootPath.resolve(SUB_DIR_PATH_NAME).resolve(TEST_FILE_NAME);
        Path relativeTestFilePath = Path.of(TEST_FILE_NAME);

        Files.writeString(testFile, "initial content", StandardCharsets.UTF_8);
        assertEvent(relativeTestFilePath, Kind.CREATE);

        Files.writeString(testFile, "modified content", StandardCharsets.UTF_8);
        assertEvent(relativeTestFilePath, Kind.MODIFY);

        Files.writeString(testFile, "modified content", StandardCharsets.UTF_8);
        assertNoEvents();

        Files.delete(testFile);
        assertEvent(relativeTestFilePath, Kind.DELETE);
    }

    @Test
    public void testFileInUnwatchedSubDir() throws IOException, InterruptedException {
        Files.createDirectories(rootPath.resolve(SUB_DIR_PATH_NAME));

        watchService.registerListener(listener, rootPath, false);

        Path testFile = rootPath.resolve(SUB_DIR_PATH_NAME).resolve(TEST_FILE_NAME);

        Files.writeString(testFile, "initial content", StandardCharsets.UTF_8);
        assertNoEvents();

        Files.writeString(testFile, "modified content", StandardCharsets.UTF_8);
        assertNoEvents();

        Files.writeString(testFile, "modified content", StandardCharsets.UTF_8);
        assertNoEvents();

        Files.delete(testFile);
        assertNoEvents();
    }

    @Test
    public void testNewSubDirAlsoWatched() throws IOException, InterruptedException {
        watchService.registerListener(listener, rootPath, true);

        Path subDirSubDir = Files.createDirectories(rootPath.resolve(SUB_DIR_PATH_NAME).resolve(SUB_DIR_PATH_NAME));
        assertNoEvents();

        Path testFile = subDirSubDir.resolve(TEST_FILE_NAME);
        Path relativeTestFilePath = rootPath.relativize(testFile);

        Files.writeString(testFile, "initial content", StandardCharsets.UTF_8);
        assertEvent(relativeTestFilePath, Kind.CREATE);

        Files.writeString(testFile, "modified content", StandardCharsets.UTF_8);
        assertEvent(relativeTestFilePath, Kind.MODIFY);

        Files.writeString(testFile, "modified content", StandardCharsets.UTF_8);
        assertNoEvents();

        Files.delete(testFile);
        assertEvent(relativeTestFilePath, Kind.DELETE);

        Files.delete(subDirSubDir);
        assertNoEvents();
    }

    // Test sequence of events that could occur in some real life environments
    @Test
    public void testMultipleEvents() throws IOException, InterruptedException {
        watchService.registerListener(listener, Path.of(""), false);
        Path testFile = rootPath.resolve(TEST_FILE_NAME).toAbsolutePath();
        Path relativeTestFilePath = Path.of(TEST_FILE_NAME);

        // reduce multiple repeating patterns
        artificiallyGenerateEvents(testFile, Arrays.asList(Kind.DELETE, Kind.CREATE, Kind.DELETE, Kind.CREATE));
        assertEvents(relativeTestFilePath, Arrays.asList(Kind.DELETE, Kind.CREATE));

        artificiallyGenerateEvents(testFile, Arrays.asList(Kind.DELETE, Kind.CREATE, Kind.MODIFY));
        assertEvents(relativeTestFilePath, Arrays.asList(Kind.DELETE, Kind.CREATE));

        artificiallyGenerateEvents(testFile,
                Arrays.asList(Kind.DELETE, Kind.CREATE, Kind.MODIFY, Kind.DELETE, Kind.CREATE, Kind.MODIFY));
        assertEvents(relativeTestFilePath, Arrays.asList(Kind.DELETE, Kind.CREATE));

        // reduce repeated MODIFY to a single event
        artificiallyGenerateEvents(testFile, Arrays.asList(Kind.MODIFY, Kind.MODIFY));
        assertEvents(relativeTestFilePath, Arrays.asList(Kind.MODIFY));

        artificiallyGenerateEvents(testFile, Arrays.asList(Kind.MODIFY, Kind.MODIFY, Kind.MODIFY));
        assertEvents(relativeTestFilePath, Arrays.asList(Kind.MODIFY));

        artificiallyGenerateEvents(testFile, Arrays.asList(Kind.MODIFY, Kind.DELETE, Kind.CREATE, Kind.MODIFY));
        assertEvents(relativeTestFilePath, Arrays.asList(Kind.DELETE, Kind.CREATE));

        artificiallyGenerateEvents(testFile, Arrays.asList(Kind.MODIFY, Kind.DELETE, Kind.CREATE));
        assertEvents(relativeTestFilePath, Arrays.asList(Kind.DELETE, Kind.CREATE));

        artificiallyGenerateEvents(testFile, Arrays.asList(Kind.CREATE, Kind.MODIFY));
        assertEvents(relativeTestFilePath, Arrays.asList(Kind.CREATE));

        artificiallyGenerateEvents(testFile,
                Arrays.asList(Kind.CREATE, Kind.MODIFY, Kind.DELETE, Kind.CREATE, Kind.MODIFY));
        assertEvents(relativeTestFilePath, Arrays.asList(Kind.CREATE));

        artificiallyGenerateEvents(testFile, Arrays.asList(Kind.CREATE, Kind.DELETE, Kind.CREATE));
        assertEvents(relativeTestFilePath, Arrays.asList(Kind.CREATE));

        artificiallyGenerateEvents(testFile, Arrays.asList(Kind.CREATE, Kind.MODIFY, Kind.DELETE));
        assertNoEvents();

        artificiallyGenerateEvents(testFile, Arrays.asList(Kind.CREATE, Kind.DELETE));
        assertNoEvents();
    }

    private void assertNoEvents() throws InterruptedException {
        Thread.sleep(5000);

        assertThat(listener.events, empty());
    }

    private void assertEvent(Path path, Kind kind) throws InterruptedException {
        waitForAssert(() -> assertThat(listener.events, not(empty())));
        Thread.sleep(500);

        assertThat(listener.events, hasSize(1));
        assertThat(listener.events, hasItem(new Event(path, kind)));
        listener.events.clear();
    }

    private void assertEvents(Path path, List<Kind> kinds) throws InterruptedException {
        waitForAssert(() -> assertThat(listener.events, not(empty())));
        Thread.sleep(500);

        var eventKinds = kinds.stream().map(kind -> new Event(path, kind)).collect(Collectors.toList());
        assertThat(listener.events, equalTo(eventKinds));
        listener.events.clear();
    }

    private void generateEvent(Path path, Kind kind) throws IOException, InterruptedException {
        final Map<Kind, DirectoryChangeEvent.EventType> kindMap = Map.of( //
                Kind.CREATE, DirectoryChangeEvent.EventType.CREATE, //
                Kind.DELETE, DirectoryChangeEvent.EventType.DELETE, //
                Kind.MODIFY, DirectoryChangeEvent.EventType.MODIFY);
        DirectoryChangeEvent event = new DirectoryChangeEvent(kindMap.get(kind), false, path, FileHash.fromLong(0), 1,
                path.getRoot());
        watchService.onEvent(event);
    }

    private void artificiallyGenerateEvents(Path path, List<Kind> kinds) throws IOException, InterruptedException {
        for (Kind kind : kinds) {
            generateEvent(path, kind);
        }
    }

    private static class TestWatchEventListener implements WatchService.WatchEventListener {
        List<Event> events = new CopyOnWriteArrayList<>();

        @Override
        public void processWatchEvent(Kind kind, Path path) {
            events.add(new Event(path, kind));
        }
    }

    record Event(Path path, Kind kind) {
    }
}
