package io.github.ppissias.jplatesolve.astap;

import java.nio.file.Files;
import java.nio.file.Path;

public class AstapProcessFixture {
    public static void main(String[] arguments) throws Exception {
        Path image = Path.of(arguments[0]);
        String mode = arguments[1];
        String filename = image.getFileName().toString();
        int extension = filename.lastIndexOf('.');
        String stem = extension > 0 ? filename.substring(0, extension) : filename;
        Files.writeString(image.resolveSibling(stem + ".pid"), Long.toString(ProcessHandle.current().pid()));
        if ("hang".equals(mode)) {
            Files.writeString(image.resolveSibling(stem + ".wcs"), "partial");
            Thread.sleep(60000);
            return;
        }
        if ("verbose".equals(mode)) {
            for (int index = 0; index < 20000; index++) System.out.println("ASTAP fixture diagnostics " + index);
        }
        if (!"missing-wcs".equals(mode)) Files.writeString(image.resolveSibling(stem + ".wcs"), "new-wcs");
        if ("reject".equals(mode)) {
            Files.writeString(image.resolveSibling(stem + ".ini"), "PLTSOLVD=F\nERROR=No solution");
            System.exit(1);
        }
        Files.writeString(image.resolveSibling(stem + ".ini"), "PLTSOLVD=T\nCRVAL1=12.5");
    }
}
