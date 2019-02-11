package com.long2know.utilities.tcxzpot.util;

import com.long2know.utilities.tcxzpot.Serializer;
import com.long2know.utilities.tcxzpot.TCXExtension;

public class TCXExtensionSerialization {
    public static void serializeExtensions(TCXExtension[] extensions, Serializer serializer) {
        if(extensions != null && extensions.length > 0) {
            serializer.print("<Extensions>");
            for(TCXExtension extension : extensions) {
                extension.serialize(serializer);
            }
            serializer.print("</Extensions>");
        }
    }
}
