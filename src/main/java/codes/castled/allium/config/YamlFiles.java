package codes.castled.allium.config;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.file.*;
/** Block-style serialization with a one-time original and atomic replacement. */
public final class YamlFiles {
 private YamlFiles(){}
 public static void normalize(Path file,YamlConfiguration yaml)throws IOException{
  String text=yaml.saveToString();if(text.equals(Files.readString(file)))return;
  Path backup=file.resolveSibling(file.getFileName()+".before-format");if(!Files.exists(backup))Files.copy(file,backup,StandardCopyOption.COPY_ATTRIBUTES);
  Path temp=Files.createTempFile(file.getParent(),".allium-yaml-",".tmp");
  try{Files.writeString(temp,text);try{Files.setPosixFilePermissions(temp,Files.getPosixFilePermissions(file));}catch(UnsupportedOperationException ignored){}Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
 }
}
