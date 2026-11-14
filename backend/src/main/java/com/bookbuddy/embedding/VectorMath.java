package com.bookbuddy.embedding;

import java.util.ArrayList;
import java.util.List;

public final class VectorMath {

    private VectorMath() {}

    /** In-place L2 normalisation. A zero vector is returned unchanged. */
    public static float[] l2Normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        double norm = Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
        return v;
    }

    public static double norm(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }

    public static double cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Cosine between a float query vector and a stored {@code List<Double>} embedding. */
    public static double cosine(float[] query, List<Double> stored) {
        if (stored == null || stored.isEmpty()) {
            return 0;
        }
        int n = Math.min(query.length, stored.size());
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < n; i++) {
            double b = stored.get(i);
            dot += (double) query[i] * b;
            na += (double) query[i] * query[i];
            nb += b * b;
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public static List<Double> toList(float[] v) {
        List<Double> out = new ArrayList<>(v.length);
        for (float x : v) {
            out.add((double) x);
        }
        return out;
    }

    public static float[] toArray(List<Double> v) {
        float[] out = new float[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = v.get(i).floatValue();
        }
        return out;
    }
}
