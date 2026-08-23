package juloo.keyboard2.prediction.history;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.io.FileDescriptor;
import java.util.HashMap;
import java.util.Map;

/** Versioned, atomically replaced history files outside Android backup data. */
public final class FileUserHistoryStore implements UserHistoryStore
{
  private static final int MAGIC = 0x55484d31;
  private static final int VERSION = 1;
  private final File directory;
  private final HistoryClock clock;

  public FileUserHistoryStore(Context context)
  { this(new File(context.getNoBackupFilesDir(), "prediction-history")); }
  public FileUserHistoryStore(File directory)
  { this(directory, System::currentTimeMillis); }
  FileUserHistoryStore(File directory, HistoryClock clock)
  {
    this.directory = directory;
    this.clock = clock;
  }

  public UserHistoryModel load(String locale)
  {
    File file = file(locale);
    if (!file.isFile()) return UserHistoryModel.empty(clock);
    if (file.length() > BoundedUserHistoryModel.MAX_BYTES) return discard(file);
    try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
      if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("invalid history");
      int count = input.readInt();
      if (count < 0 || count > BoundedUserHistoryModel.MAX_ENTRIES) throw new IOException("invalid count");
      Map<String, UserHistoryModel.Entry> entries = new HashMap<>();
      for (int i = 0; i < count; i++) {
        String key = input.readUTF();
        int value = input.readInt();
        long updatedAt = input.readLong();
        if (value <= 0) throw new IOException("invalid entry");
        entries.put(key, new UserHistoryModel.Entry(value, updatedAt));
      }
      if (input.read() != -1) throw new IOException("trailing data");
      UserHistoryModel result = new UserHistoryModel(entries, clock);
      if (result.serializedBytes() > BoundedUserHistoryModel.MAX_BYTES) throw new IOException("oversized history");
      return result;
    } catch (IOException invalid) {
      return discard(file);
    }
  }

  public void save(String locale, UserHistoryModel snapshot)
  {
    if (snapshot.serializedBytes() > BoundedUserHistoryModel.MAX_BYTES)
      throw new IllegalArgumentException("history exceeds byte limit");
    if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("cannot create history directory");
    File temporary = new File(directory, locale + ".bin.tmp");
    try (FileOutputStream stream = new FileOutputStream(temporary);
         DataOutputStream output = new DataOutputStream(stream)) {
      output.writeInt(MAGIC);
      output.writeInt(VERSION);
      output.writeInt(snapshot.entries().size());
      for (Map.Entry<String, UserHistoryModel.Entry> entry : snapshot.entries().entrySet()) {
        output.writeUTF(entry.getKey());
        output.writeInt(entry.getValue().count);
        output.writeLong(entry.getValue().updatedAt);
      }
      output.flush();
      stream.getFD().sync();
    } catch (IOException failure) {
      temporary.delete();
      throw new IllegalStateException("cannot save history", failure);
    }
    if (!replace(temporary, file(locale))) {
      temporary.delete();
      throw new IllegalStateException("cannot replace history");
    }
  }

  private UserHistoryModel discard(File file)
  {
    file.delete();
    return UserHistoryModel.empty(clock);
  }

  private static boolean replace(File source, File destination)
  {
    try {
      Class<?> os = Class.forName("android.system.Os");
      Method rename = os.getMethod("rename", String.class, String.class);
      rename.invoke(null, source.getPath(), destination.getPath());
      syncDirectory(os, destination.getParentFile());
      return true;
    } catch (ClassNotFoundException | NoSuchMethodException unavailable) {
      return source.renameTo(destination);
    } catch (IllegalAccessException | InvocationTargetException failed) {
      return source.renameTo(destination);
    }
  }

  /** Android's public API lacks a directory stream; use the API-21 POSIX bridge when present. */
  private static void syncDirectory(Class<?> os, File directory)
  {
    if (directory == null) return;
    try {
      int readOnly = Class.forName("android.system.OsConstants").getField("O_RDONLY").getInt(null);
      Method open = os.getMethod("open", String.class, int.class, int.class);
      FileDescriptor descriptor = (FileDescriptor) open.invoke(null, directory.getPath(), readOnly, 0);
      try { os.getMethod("fsync", FileDescriptor.class).invoke(null, descriptor); }
      finally { os.getMethod("close", FileDescriptor.class).invoke(null, descriptor); }
    } catch (ReflectiveOperationException unavailable) {
      // JVM tests and non-POSIX environments retain the already fsynced temporary file.
    }
  }

  private File file(String locale)
  {
    if (locale.indexOf('/') >= 0 || locale.indexOf(File.separatorChar) >= 0)
      throw new IllegalArgumentException("invalid locale");
    return new File(directory, locale + ".bin");
  }
}
