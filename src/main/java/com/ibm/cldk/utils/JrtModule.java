package com.ibm.cldk.utils;

import com.ibm.wala.classLoader.Module;
import com.ibm.wala.classLoader.ModuleEntry;
import com.ibm.wala.core.util.shrike.ShrikeClassReaderHandle;
import com.ibm.wala.core.util.strings.ImmutableByteArray;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A WALA {@link Module} over one named module of the running JVM, read through the {@code jrt:/}
 * filesystem ({@code lib/modules}).
 *
 * <p>This exists because WALA's own {@code com.ibm.wala.core.java11.JrtModule} (still in 1.8.0)
 * returns a lazy stream from inside a try-with-resources that closes it first, so iterating the
 * entries fails with {@code IllegalStateException: source already consumed or closed}. Here the
 * walk is done eagerly in the constructor; entries are held as paths and read on demand.
 *
 * <p>Reading the primordial scope from the JVM that is executing the analysis (rather than from
 * {@code $JAVA_HOME/jmods}) means any runtime works, including jlink'd ones that ship no jmods.
 */
public final class JrtModule implements Module {

  private final Path root;
  private final List<Path> files;

  public JrtModule(String module) throws IOException {
    root = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("modules", module);
    try (Stream<Path> walk = Files.walk(root)) {
      files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
    }
  }

  @Override
  public String toString() {
    return "[module " + root + "]";
  }

  @Override
  public Iterator<? extends ModuleEntry> getEntries() {
    return files.stream().map(this::entry).iterator();
  }

  private ModuleEntry entry(Path path) {
    Module container = this;
    String name = root.relativize(path).toString();
    return new ModuleEntry() {
      private String className;

      @Override
      public String getName() {
        return name;
      }

      @Override
      public boolean isClassFile() {
        return name.endsWith(".class");
      }

      @Override
      public boolean isSourceFile() {
        return name.endsWith(".java");
      }

      @Override
      public InputStream getInputStream() {
        try {
          return new ByteArrayInputStream(Files.readAllBytes(path));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      @Override
      public boolean isModuleFile() {
        return false;
      }

      @Override
      public Module asModule() {
        throw new UnsupportedOperationException(name + " is not a module");
      }

      @Override
      public String getClassName() {
        if (className == null) {
          try {
            className = ImmutableByteArray.make(new ShrikeClassReaderHandle(this).get().getName()).toString();
          } catch (InvalidClassFileException e) {
            throw new IllegalStateException("Invalid class file " + name, e);
          }
        }
        return className;
      }

      @Override
      public Module getContainer() {
        return container;
      }

      @Override
      public String toString() {
        return "[" + (isClassFile() ? "class" : "file") + " " + name + "]";
      }
    };
  }
}
