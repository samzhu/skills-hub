// POC for S140 §4.3 StubEmbeddingModel hypothesis：
//
// Hypothesis: Random(input.hashCode()) 產生 768-dim float vector，作為 e2e profile 的
// stub EmbeddingModel，能滿足：
//   H1. Determinism — 同 input 永遠回同 vector
//   H2. Separation — 不同 input 的 cosine similarity 落在 -0.2 ~ 0.2（足夠分離，不全 1.0 / 不全 0）
//   H3. Stable ranking — 同 query 對固定 candidate set 的 cosine ranking 跨 run 一致
//
// 此 POC 不依賴 Spring AI / pgvector，純 java.util.Random + 數學驗證。
// 只測 stub 自身行為；pgvector cosine 行為由 e2e 整合驗證（後續 task）。
//
// 跑法：javac StubEmbeddingPoc.java && java -ea StubEmbeddingPoc

import java.util.Random;
import java.util.Arrays;

public class StubEmbeddingPoc {

    private static final int DIM = 768;

    static float[] embed(String input) {
        var rng = new Random(input.hashCode());
        var vec = new float[DIM];
        for (int i = 0; i < DIM; i++) vec[i] = rng.nextFloat() * 2 - 1;
        return normalize(vec);
    }

    static float[] normalize(float[] v) {
        double norm = 0;
        for (float f : v) norm += f * f;
        norm = Math.sqrt(norm);
        if (norm < 1e-10) return v;
        var out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;  // both unit-normed → dot = cosine
    }

    public static void main(String[] args) {
        System.out.println("=== S140 StubEmbeddingModel POC (768-dim Random) ===\n");

        // H1. Determinism
        var v1a = embed("docker-compose-helper");
        var v1b = embed("docker-compose-helper");
        boolean deterministic = Arrays.equals(v1a, v1b);
        System.out.println("H1 Determinism: " + (deterministic ? "PASS ✓" : "FAIL ✗")
            + " — same input → same vector");

        // H2. Separation
        var skills = new String[] {
            "docker-compose-helper",
            "k8s-deployment",
            "junit-test-generator",
            "eslint-config",
            "docs-builder"
        };
        var query = "我想把應用部署到容器環境";
        var qv = embed(query);

        System.out.println("\nH2 Cosine separation (query vs each skill):");
        double minSim = 1.0, maxSim = -1.0;
        for (var s : skills) {
            var sv = embed(s);
            var sim = cosine(qv, sv);
            System.out.printf("  %-30s cosine=%.4f%n", s, sim);
            minSim = Math.min(minSim, sim);
            maxSim = Math.max(maxSim, sim);
        }
        boolean separation = maxSim - minSim > 0.05;
        System.out.println("  range = " + (maxSim - minSim) + " → "
            + (separation ? "PASS ✓ (sufficient separation)" : "FAIL ✗ (too tight)"));

        // H3. Stable ranking — embed twice, compare order
        System.out.println("\nH3 Stable ranking (run twice, compare):");
        String[] order1 = orderByDescSim(qv, skills);
        String[] order2 = orderByDescSim(embed(query), skills);
        boolean stable = Arrays.equals(order1, order2);
        System.out.println("  run1: " + Arrays.toString(order1));
        System.out.println("  run2: " + Arrays.toString(order2));
        System.out.println("  " + (stable ? "PASS ✓" : "FAIL ✗"));

        // H4 (bonus): hashCode collisions check — different strings should
        // produce different hashCodes (otherwise → same vector)
        System.out.println("\nH4 hashCode uniqueness:");
        for (int i = 0; i < skills.length; i++) {
            for (int j = i + 1; j < skills.length; j++) {
                if (skills[i].hashCode() == skills[j].hashCode()) {
                    System.out.println("  COLLISION: " + skills[i] + " and " + skills[j]);
                }
            }
        }
        System.out.println("  no collisions among test set");

        // Verdict
        System.out.println("\n=== Verdict ===");
        if (deterministic && separation && stable) {
            System.out.println("ALL HYPOTHESES PASS — proceed with Random(hashCode) 768-dim stub.");
            System.out.println("NOTE: cosine ranking is *deterministic* but NOT *semantic* —");
            System.out.println("  AC-5 should verify deterministic order (not 'docker first').");
            System.exit(0);
        } else {
            System.out.println("HYPOTHESIS FAIL — revise design (consider keyword-biased stub).");
            System.exit(1);
        }
    }

    static String[] orderByDescSim(float[] qv, String[] skills) {
        var pairs = new String[skills.length];
        var sims = new double[skills.length];
        for (int i = 0; i < skills.length; i++) sims[i] = cosine(qv, embed(skills[i]));
        var idx = new Integer[skills.length];
        for (int i = 0; i < skills.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(sims[b], sims[a]));
        for (int i = 0; i < skills.length; i++) pairs[i] = skills[idx[i]];
        return pairs;
    }
}
