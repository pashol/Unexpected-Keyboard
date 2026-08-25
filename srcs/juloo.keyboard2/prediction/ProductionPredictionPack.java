package juloo.keyboard2.prediction;

import android.content.res.AssetManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A ready prediction pack selected from the APK's reviewed language-pack registry. */
public final class ProductionPredictionPack
{
  private static final Pattern STRING_FIELD = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
  private final String _dictionary_asset;
  private final String _manifest_asset;
  private final String _locale;
  private final String _sha256;

  private ProductionPredictionPack(String dictionary_asset, String manifest_asset, String locale, String sha256)
  {
    _dictionary_asset = dictionary_asset;
    _manifest_asset = manifest_asset;
    _locale = locale;
    _sha256 = sha256;
  }

  public String dictionary_asset() { return _dictionary_asset; }

  public static ProductionPredictionPack load(AssetManager assets, String language_tag) throws IOException
  {
    ProductionPredictionPack selected = select(read(assets.open("latinime/packs/language_packs.json")), language_tag);
    if (selected == null)
      return null;
    String manifest = read(assets.open("latinime/packs/" + selected._manifest_asset));
    String sha256 = field(manifest, "output_sha256");
    if (sha256 == null || !sha256.matches("[0-9a-f]{64}"))
      throw new IOException("Prediction pack manifest has no valid output hash");
    return new ProductionPredictionPack(selected._dictionary_asset, selected._manifest_asset,
        selected._locale, sha256);
  }

  static ProductionPredictionPack select(String registry, String language_tag)
  {
    if (language_tag == null)
      return null;
    Locale requested = Locale.forLanguageTag(language_tag);
    String requested_tag = requested.toLanguageTag();
    ArrayList<ProductionPredictionPack> packs = new ArrayList<ProductionPredictionPack>();
    for (String object : objects(registry))
    {
      String state = field(object, "state");
      String dictionary = field(object, "dictionary");
      String manifest = field(object, "manifest");
      String locale = field(object, "locale");
      if (!"ready".equals(state) || dictionary == null || manifest == null || locale == null)
        continue;
      ProductionPredictionPack pack = new ProductionPredictionPack(dictionary, manifest, locale, null);
      if (Locale.forLanguageTag(locale).toLanguageTag().equals(requested_tag))
        return pack;
      packs.add(pack);
    }
    for (ProductionPredictionPack pack : packs)
      if (Locale.forLanguageTag(pack._locale).getLanguage().equals(requested.getLanguage()))
        return pack;
    return null;
  }

  static boolean matches_sha256(File file, String expected) throws IOException
  {
    return matches_sha256(new FileInputStream(file), expected);
  }

  public static boolean matches_sha256(AssetManager assets, ProductionPredictionPack pack) throws IOException
  {
    return matches_sha256(assets.open("latinime/packs/" + pack._dictionary_asset), pack._sha256);
  }

  private static boolean matches_sha256(InputStream input, String expected) throws IOException
  {
    try
    {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try
      {
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;)
          digest.update(buffer, 0, count);
      }
      finally
      {
        input.close();
      }
      StringBuilder actual = new StringBuilder();
      for (byte value : digest.digest())
        actual.append(String.format("%02x", value & 0xff));
      return actual.toString().equals(expected);
    }
    catch (NoSuchAlgorithmException e)
    {
      throw new IOException("SHA-256 is unavailable", e);
    }
  }

  private static ArrayList<String> objects(String registry)
  {
    ArrayList<String> result = new ArrayList<String>();
    int depth = 0;
    int start = -1;
    boolean quoted = false;
    for (int index = 0; index < registry.length(); ++index)
    {
      char character = registry.charAt(index);
      if (character == '"' && (index == 0 || registry.charAt(index - 1) != '\\'))
        quoted = !quoted;
      if (quoted)
        continue;
      if (character == '{' && depth++ == 1)
        start = index;
      else if (character == '}' && --depth == 1 && start >= 0)
      {
        result.add(registry.substring(start, index + 1));
        start = -1;
      }
    }
    return result;
  }

  private static String field(String json, String name)
  {
    Matcher matcher = STRING_FIELD.matcher(json);
    while (matcher.find())
      if (name.equals(matcher.group(1)))
        return matcher.group(2);
    return null;
  }

  private static String read(InputStream input) throws IOException
  {
    StringBuilder result = new StringBuilder();
    try
    {
      byte[] buffer = new byte[8192];
      for (int count; (count = input.read(buffer)) != -1;)
        result.append(new String(buffer, 0, count, "UTF-8"));
      return result.toString();
    }
    finally
    {
      input.close();
    }
  }
}
