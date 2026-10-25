package tv.voxclean.launcher;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small line files in the app's private storage (the tile order, the Library's choices), written atomically. A file
 * that exists but cannot be read is never overwritten: saving refuses until it reads again.
 */
final class Store {
    static final String ORDER = "order.txt", LIBRARY = "library.txt";
    private static final String TAG = "Vox";

    private final AtomicFile file;
    private boolean readable = true;

    Store(Context context, String name) {
        file = new AtomicFile(new File(context.getFilesDir(), name));
    }

    /** @return the lines, empty when there is no file yet or it could not be read */
    List<String> read() {
        try {
            byte[] bytes = file.readFully();
            readable = true;
            List<String> out = new ArrayList<>();
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                if (!line.trim().isEmpty()) out.add(line.trim());
            }
            return out;
        } catch (FileNotFoundException e) {
            readable = true;
            return Collections.emptyList();
        } catch (IOException | SecurityException e) {
            readable = false;
            Log.e(TAG, file.getBaseFile() + " could not be read; it will not be overwritten", e);
            return Collections.emptyList();
        }
    }

    /** @return false when nothing was written (the file was unreadable, or the write failed) */
    boolean write(List<String> lines) {
        if (!readable) {
            Log.e(TAG, "not saving " + file.getBaseFile() + ": it could not be read, so it is kept as it is");
            return false;
        }
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
            return true;
        } catch (IOException e) {
            if (out != null) file.failWrite(out);
            Log.e(TAG, "could not save " + file.getBaseFile() + " (" + lines.size() + " lines)", e);
            return false;
        }
    }
}
