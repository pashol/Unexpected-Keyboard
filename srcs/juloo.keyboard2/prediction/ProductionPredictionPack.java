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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A ready prediction pack selected from the APK's reviewed language-pack registry. */
public final class ProductionPredictionPack
{
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
    return with_manifest(selected, read(assets.open("latinime/packs/" + selected._manifest_asset)));
  }

  static ProductionPredictionPack with_manifest(ProductionPredictionPack selected, String json) throws IOException
  {
    JSONObject manifest = object(json);
    require_keys(manifest, new String[] {
        "combined_source_sha256", "compiler", "format_version", "locale", "output_sha256", "sources", "timestamp",
    });
    if (!(manifest.opt("compiler") instanceof JSONObject) || !(manifest.opt("sources") instanceof JSONObject)
        || manifest.optInt("format_version", -1) != 202 || !selected._locale.equals(string(manifest, "locale")))
      throw new IOException("Prediction pack manifest has an invalid schema");
    String sha256 = string(manifest, "output_sha256");
    if (!sha256.matches("[0-9a-f]{64}"))
      throw new IOException("Prediction pack manifest has no valid output hash");
    return new ProductionPredictionPack(selected._dictionary_asset, selected._manifest_asset, selected._locale, sha256);
  }

  static ProductionPredictionPack select(String registry, String language_tag) throws IOException
  {
    if (language_tag == null)
      return null;
    JSONObject root = object(registry);
    require_keys(root, new String[] { "format_version", "packs" });
    if (root.optInt("format_version", -1) != 202 || !(root.opt("packs") instanceof JSONArray))
      throw new IOException("Prediction pack registry has an invalid schema");
    Locale requested = Locale.forLanguageTag(language_tag);
    String requested_tag = requested.toLanguageTag();
    ArrayList<ProductionPredictionPack> packs = new ArrayList<ProductionPredictionPack>();
    JSONArray entries = root.optJSONArray("packs");
    for (int index = 0; index < entries.length(); ++index)
    {
      if (!(entries.opt(index) instanceof JSONObject))
        throw new IOException("Prediction pack registry entries must be objects");
      JSONObject entry = entries.optJSONObject(index);
      if (!"ready".equals(entry.opt("state")))
        continue;
      String dictionary = asset_name(entry, "dictionary");
      String manifest = asset_name(entry, "manifest");
      String locale = string(entry, "locale");
      if (!"ready".equals(string(entry, "state")))
        throw new IOException("Prediction pack registry has an invalid ready entry");
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

  private static JSONObject object(String json) throws IOException
  {
    try { return new JSONObject(json); }
    catch (JSONException error) { throw new IOException("Prediction pack JSON is invalid", error); }
  }

  private static String string(JSONObject object, String name) throws IOException
  {
    Object value = object.opt(name);
    if (!(value instanceof String) || ((String)value).isEmpty())
      throw new IOException("Prediction pack JSON field " + name + " must be a nonempty string");
    return (String)value;
  }

  private static String asset_name(JSONObject object, String name) throws IOException
  {
    String value = string(object, name);
    if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || ".".equals(value) || "..".equals(value))
      throw new IOException("Prediction pack asset name must be a basename");
    return value;
  }

  private static void require_keys(JSONObject object, String[] expected) throws IOException
  {
    if (object.length() != expected.length)
      throw new IOException("Prediction pack JSON has unexpected fields");
    for (String name : expected)
      if (!object.has(name))
        throw new IOException("Prediction pack JSON is missing " + name);
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
      finally { input.close(); }
      StringBuilder actual = new StringBuilder();
      for (byte value : digest.digest())
        actual.append(String.format("%02x", value & 0xff));
      return actual.toString().equals(expected);
    }
    catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 is unavailable", e); }
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
    finally { input.close(); }
  }
}
