package com.articulate.nlp;

import com.articulate.sigma.*;
import java.io.File;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.Iterator;
import java.util.Arrays;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.articulate.sigma.wordNet.WordNet;
import com.articulate.sigma.wordNet.WordNetUtilities;


/** ***************************************************************
 *  Uses OLLAMA to build a morphological database.
 */
public class GenMorphoDB {

    public static boolean debug = true;
    public static TreeMap<String,Set<String>> nounSynsetHash;   // Words in root form are String keys,
    public static TreeMap<String,Set<String>> verbSynsetHash;   // String values are 8-digit synset lists.
    public static TreeMap<String,Set<String>> adjectiveSynsetHash; // Using TreeMaps to leverage
    public static TreeMap<String,Set<String>> adverbSynsetHash;    // alphabetical order

    public static Map<String,String> verbDocumentationHash;       // Keys are synset Strings, values
    public static Map<String,String> adjectiveDocumentationHash;  // are documentation strings.
    public static Map<String,String> adverbDocumentationHash;
    public static Map<String,String> nounDocumentationHash;


    /** ***************************************************************
     *  Used to filter out non-English words and numbers
     */
    public static void removeNonEnglishWords(Map<String,Set<String>> wordMap, String wordMapName) {

        Iterator<Map.Entry<String, Set<String>>> iterator = wordMap.entrySet().iterator();
        int numDeleted = 0;
        while (iterator.hasNext()) {
            Map.Entry<String, Set<String>> entry = iterator.next();
            String key = entry.getKey();
            if (!key.matches("^[a-zA-Z_.\\'-]+$")) {
                iterator.remove();  // Safe removal via iterator
                numDeleted++;
            }
        }
        System.out.println("Non-English words removed from " + wordMapName + ": " + numDeleted);
    }


    /** ****************************************************************
     * Returns the indefinite article ("a" or "an")
     * based only on the first letter of the given noun.
     */
    public static boolean isIrregularIndefiniteArticle(String article, String noun) {

        char firstChar = Character.toLowerCase(noun.trim().charAt(0));
        if ("aeiou".indexOf(firstChar) >= 0) {
            return "an".equals(article);
        } else {
            return "a".equals(article);
        }
    }


    /*****************************************************************************
     * Determines if a plural returned is irregular
     */
    public static boolean isIrregularPlural(String singular, String givenPlural) {
        if (singular == null || singular.isEmpty() || givenPlural == null || givenPlural.isEmpty()) {
            return false;
        }

        String expectedPlural = pluralize(singular);
        return !expectedPlural.equalsIgnoreCase(givenPlural);
    }

    // Generic pluralization rules
    private static String pluralize(String word) {
        word = word.toLowerCase();
        int len = word.length();

        // consonant + y → ies
        if (len > 1 && word.endsWith("y") && isConsonant(word.charAt(len - 2))) {
            return word.substring(0, len - 1) + "ies";
        }

        // ends with s, sh, ch, x, z → es
        if (word.endsWith("s") || word.endsWith("sh") || word.endsWith("ch") ||
                word.endsWith("x") || word.endsWith("z")) {
            return word + "es";
        }

        // ends with fe → ves
        if (word.endsWith("fe")) {
            return word.substring(0, len - 2) + "ves";
        }

        // ends with f → ves
        if (word.endsWith("f")) {
            return word.substring(0, len - 1) + "ves";
        }

        // ends with o → oes (simplified rule, many exceptions exist)
        if (word.endsWith("o")) {
            return word + "es";
        }

        // default: add s
        return word + "s";
    }

    private static boolean isConsonant(char c) {
        return "bcdfghjklmnpqrstvwxyz".indexOf(Character.toLowerCase(c)) != -1;
    }

    /** ***************************************************************
     *  Uses OLLAMA to determine the indefinite article of every noun
     *  in WordNet.
     */
    public static void genIndefiniteArticles() {

        String indefFileName = "IndefiniteArticles_" + GenUtils.OLLAMA_MODEL + ".txt";
        for (Map.Entry<String, Set<String>> entry : nounSynsetHash.entrySet()) {
            String term = entry.getKey().replace('_', ' ');
            if (term.length() < 2) continue;
            for (String sysnsetId : entry.getValue()) {
                String definition = nounDocumentationHash.get(sysnsetId);
                definition = (definition != null) ? definition.replaceAll("^\"|\"$", "") : null;
                String definitionStatement = (definition == null) ? "" : "Definition: \"" + nounDocumentationHash.get(sysnsetId) + "\". ";
                String prompt = "You are an expert linguist specializing in English noun phrase syntax and article usage. " +
                        "Your task is to determine the correct indefinite article usage for the given noun or noun phrase. \n\n" +
                        "The noun to classify: \"" + term + "\". \n" +
                        definitionStatement + "\n\n" +
                        "Instructions: \n" +
                        "- Decide whether the correct indefinite article is \"a\", \"an\", or \"none\".\n" +
                        "- Base your decision on pronunciation and grammatical convention.\n" +
                        "- If the noun is a scientific or proper name that normally does not take an article, return \"none\".\n" +
                        "- If an article applies only in specific usage contexts, still provide the indefinite article.\n" +
                        "- Always provide:\n" +
                        "  1. The chosen article.\n" +
                        "  2. The noun exactly as given.\n" +
                        "  3. A clear explanation of the rationale.\n" +
                        "  4. An example sentence showing the article in use (or stating why no article is used).\n\n" +
                        "Important formatting rules:\n" +
                        " * Output only valid JSON.\n" +
                        " * All JSON strings must escape quotation marks (\" → \\\") and (' → \\')\n" +
                        " * Do not include any commentary outside the JSON.\n\n" +
                        "Output strictly in this JSON format (all lowercase for the article):" +
                        "\n\n```json\n{\n  \"article\": \"<a|an|none>\",\n  \"noun\": \"<noun>\",\n  \"explanation\":\"<rationale for classification>\",\n  \"usage\":\"<example sentence with article>\" \n}";
                if (debug) System.out.println("GenMorphoDB.genIndefiniteArticles() Prompt: " + prompt);
                String llmResponse = GenUtils.askOllama(prompt);
                String jsonResponse = GenUtils.extractFirstJsonObject(llmResponse);
                boolean ERROR_IN_RESPONSE = true;
                if (jsonResponse != null) {
                    String[] indefArticleArray = GenUtils.extractJsonFields(jsonResponse, Arrays.asList("article", "noun", "explanation", "usage"));
                    if (indefArticleArray != null) {
                        ERROR_IN_RESPONSE = false;
                        String category;
                        indefArticleArray[2] = "\"" + indefArticleArray[2] + "\""; //explanation
                        indefArticleArray[3] = "\"" + indefArticleArray[3] + "\""; //usage
                        if (indefArticleArray[0].equals("none"))
                            category = "NA";
                        else if (isIrregularIndefiniteArticle(indefArticleArray[0], indefArticleArray[1]))
                            category = "Irregular";
                        else
                            category = "Regular";

                        indefArticleArray = GenUtils.appendToStringArray(indefArticleArray, category);
                        indefArticleArray = GenUtils.appendToStringArray(indefArticleArray, "\"" + definition + "\"");
                        GenUtils.writeToFile(indefFileName, Arrays.toString(indefArticleArray) + "\n");
                    }
                }
                if (ERROR_IN_RESPONSE)
                    GenUtils.writeToFile(indefFileName, "ERROR! term: " + term + ". " + definitionStatement + " - LLM response: " + llmResponse.replace("\n", "") + "\n");

                if (debug) System.out.println("\n\nGenMorphoDB.genIndefiniteArticles().LLMResponse: " + llmResponse + "\n\n**************\n");
            }
        }
    }


    

    // Plurals
    /** **********************************************************************
     *  Uses OLLAMA to determine the singular and plural form of every noun
     *  in WordNet.
     */
    public static void genPlurals() {
        String pluralsFileName = "Plurals" + GenUtils.OLLAMA_MODEL + ".txt";
        for (Map.Entry<String, Set<String>> entry : nounSynsetHash.entrySet()) {
            String term = entry.getKey().replace('_', ' ');
            if (term.length() < 2) continue;
            for (String sysnsetId : entry.getValue()) {
                String definition = nounDocumentationHash.get(sysnsetId);
                definition = (definition != null) ? definition.replaceAll("^\"|\"$", "") : null;
                String definitionStatement = (definition == null) ? "" : "Definition: \"" + nounDocumentationHash.get(sysnsetId) + "\". ";
                String prompt = "You are an expert linguist specializing in English noun phrase syntax and plural forms. " +
                        "Your task is to determine the singular and plural form of the given noun or noun phrase. \n\n" +
                        "The noun to classify: \"" + term + "\". \n" +
                        definitionStatement + "\n\n" +
                        "Important formatting rules:\n" +
                        " * Output only valid JSON.\n" +
                        " * Do not include any commentary outside the JSON.\n" +
                        "Output strictly in this JSON format (all lowercase for the article):" +
                        "\n\n```json\n{\n  \"singular\": \"<noun in singular form>\",\n  \"plural\": \"<noun in plural form>\",\n  \"explanation\":\"<rationale for classification>\" \n}";
                if (debug) System.out.println("GenMorphoDB.genPlurals() Prompt: " + prompt);
                String llmResponse = GenUtils.askOllama(prompt);
                String jsonResponse = GenUtils.extractFirstJsonObject(llmResponse);
                boolean ERROR_IN_RESPONSE = true;
                if (jsonResponse != null) {
                    String[] pluralsArray = GenUtils.extractJsonFields(jsonResponse, Arrays.asList("singular", "plural"));
                    if (pluralsArray != null) {
                        ERROR_IN_RESPONSE = false;
                        String category;
                        if (!pluralsArray[1].equals(term) && isIrregularPlural(pluralsArray[0], pluralsArray[1]))
                            category = "Irregular";
                        else
                            category = "Regular";

                        pluralsArray = GenUtils.appendToStringArray(pluralsArray, category);
                        pluralsArray = GenUtils.appendToStringArray(pluralsArray, "\"" + definition + "\"");
                        GenUtils.writeToFile(pluralsFileName, Arrays.toString(pluralsArray) + "\n");
                    }
                }
                if (ERROR_IN_RESPONSE)
                    GenUtils.writeToFile(pluralsFileName, "ERROR! term: " + term + ". " + definitionStatement + " - LLM response: " + llmResponse.replace("\n", "") + "\n");

                if (debug) System.out.println("\n\nGenMorphoDB.genPlurals().LLMResponse: " + llmResponse + "\n\n**************\n");
            }
        }
    }

    // Past tenses, other verb tenses

    // Progressives

    // Prepositions. Contingent on the verb, direct object and sometimes the indirect object
    // Do comparison against verb frames in word net, . Wordnet probably admits cases that dont work very general

    // Coens-Kappa measure of intersubject agreement.

    // Pairing adjectives with nouns and adverbs with verbs. (List all the adverbs that go with this verb).
    // Out of all the adjectives, which one fits with this noun.

    // Stative vs. Performative processes
    // Different types of verbs

    /**********************************************************************************
     *
     * @param HashMap
     */
    public static void printSynsetHash(Map<String, Set<String>> wordHash, Map<String,String> documentationHash) {

        for (Map.Entry<String, Set<String>> entry : wordHash.entrySet()) {
            String key = entry.getKey();
            Set<String> values = entry.getValue();
            System.out.println(key + " : " + values);
            for (String value : values) {
                System.out.println("    " + value + ": " + documentationHash.get(value));
            }
        }
    }

    /***************************************************************
     *  Displays usage information
     */
    public static void printHelp() {

        System.out.println("Usage: com.articulate.nlp.GenMorphoDB <gen-function> <model>");
        System.out.println("gen-functions included:");
        System.out.println("  -i to generate indefinite articles");
        System.out.println("  -p to generate plurals");
        System.out.println("Example: java -Xmx40g -classpath $SIGMANLP_CP com.articulate.nlp.GenMorphoDB -i llama3.2");
    }

    public static void main(String [] args) {

        System.out.println("Starting Generate Morphological Database");

        if (args.length != 2) {
            printHelp();
        }

        GenUtils.setOllamaModel(args[1]);
        System.out.println("Using model: " + GenUtils.OLLAMA_MODEL);
        KBmanager.getMgr().setPref("kbDir", System.getenv("SIGMA_HOME") + File.separator + "KBs");
        WordNet.initOnce();

        nounSynsetHash = new TreeMap<>(WordNet.wn.nounSynsetHash);
        verbSynsetHash = new TreeMap<>(WordNet.wn.verbSynsetHash);
        adjectiveSynsetHash = new TreeMap<>(WordNet.wn.adjectiveSynsetHash);
        adverbSynsetHash = new TreeMap<>(WordNet.wn.adverbSynsetHash);

        removeNonEnglishWords(nounSynsetHash, "noun set");
        removeNonEnglishWords(verbSynsetHash, "verb set");
        removeNonEnglishWords(adjectiveSynsetHash, "adjective set");
        removeNonEnglishWords(adverbSynsetHash, "adverb set");

        nounDocumentationHash = WordNet.wn.nounDocumentationHash;
        verbDocumentationHash = WordNet.wn.verbDocumentationHash;
        adverbDocumentationHash = WordNet.wn.adverbDocumentationHash;
        adjectiveDocumentationHash = WordNet.wn.adjectiveDocumentationHash;

        // printSynsetHash(nounSynsetHash, nounDocumentationHash);
        System.out.println("Noun set size      : " + nounSynsetHash.size());
        System.out.println("Verb set size      : " + verbSynsetHash.size());
        System.out.println("Adjective set size : " + adjectiveSynsetHash.size());
        System.out.println("Adverb set size    : " + adverbSynsetHash.size());

        String genFunction = args[0];
        switch (genFunction) {
            case "-i":
                genIndefiniteArticles();
                break;
            case "-p":
                genPlurals();
                break;
            default:
                printHelp();
                break;
        }
    }
}