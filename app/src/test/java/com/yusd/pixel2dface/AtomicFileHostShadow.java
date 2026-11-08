package com.yusd.pixel2dface;

import android.util.AtomicFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Match Android's replace-existing rename on Windows; all other AtomicFile I/O stays real. */
@Implements(AtomicFile.class)
public final class AtomicFileHostShadow {
    @Implementation(minSdk = 30)
    protected static void rename(File source, File target) throws IOException {
        Files.move(source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
