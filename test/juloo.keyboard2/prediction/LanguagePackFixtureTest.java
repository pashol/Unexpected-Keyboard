package juloo.keyboard2.prediction;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.junit.Test;
import static org.junit.Assert.*;

public class LanguagePackFixtureTest
{
  @Test
  public void english_source_fixture_contains_required_words_and_bigrams()
      throws Exception
  {
    String source = read("minimal_en.combined");

    assertTrue(source.contains("dictionary=main:en,locale=en,version=1,date="));
    assertTrue(source.contains("word=hello,f="));
    assertTrue(source.contains("word=help,f="));
    assertTrue(source.contains("word=held,f="));
    assertTrue(source.contains("word=world,f="));
    assertTrue(source.contains("word=word,f="));
    assertTrue(source.contains("word=cafe,f="));
    assertTrue(source.contains("word=caf\u00e9,f="));
    assertTrue(source.contains("word=naive,f="));
    assertTrue(source.contains("word=na\u00efve,f="));
    assertTrue(source.contains("word=hello,f=100\nbigram=there,f=60\nbigram=world,f=80"));
  }

  @Test
  public void swiss_german_source_fixture_preserves_regional_variants()
      throws Exception
  {
    String source = read("minimal_gsw.combined");

    assertTrue(source.contains("dictionary=main:gsw,locale=gsw,version=1,date="));
    assertTrue(source.contains("word=n\u00f6d,f="));
    assertTrue(source.contains("word=nid,f="));
    assertTrue(source.contains("word=ned,f="));
    assertTrue(source.contains("word=chunsch,f="));
    assertTrue(source.contains("word=chumm,f="));
    assertTrue(source.contains("word=isch,f="));
    assertTrue(source.contains("word=guet,f="));
    assertTrue(source.contains("word=das,f=90\nbigram=isch,f=80"));
    assertTrue(source.contains("word=isch,f=100\nbigram=guet,f=90"));
  }

  @Test
  public void checked_in_dictionaries_match_manifest_hashes() throws Exception
  {
    String manifest = read("manifest.json");
    assertTrue(manifest.contains("\"format\": 202"));
    assertTrue(manifest.contains("\"source_licenses\""));
    assertTrue(manifest.contains("\"source_hashes\""));
    assertTrue(manifest.contains("\"build_tool_commit\""));
    assertTrue(manifest.contains("\"build_timestamp\""));
    assertTrue(manifest.contains(hex(sha256("minimal_en.dict"))));
    assertTrue(manifest.contains(hex(sha256("minimal_gsw.dict"))));
  }

  private static String read(String name) throws Exception
  {
    return new String(Files.readAllBytes(fixture(name).toPath()),
        StandardCharsets.UTF_8);
  }

  private static byte[] sha256(String name) throws Exception
  {
    return MessageDigest.getInstance("SHA-256").digest(
        Files.readAllBytes(fixture(name).toPath()));
  }

  private static String hex(byte[] bytes)
  {
    StringBuilder value = new StringBuilder();
    for (byte b : bytes)
      value.append(String.format("%02x", b & 0xff));
    return value.toString();
  }

  private static File fixture(String name)
  {
    return new File("test/fixtures/latinime", name);
  }
}
