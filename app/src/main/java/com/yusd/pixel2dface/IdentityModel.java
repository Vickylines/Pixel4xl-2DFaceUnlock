package com.yusd.pixel2dface;

import java.util.Arrays;
import java.util.List;

/**
 * Personalized two-signal identity model built from texture and landmark geometry.
 * It reuses one ML Kit result and one LBP descriptor, so it adds no model inference.
 */
final class IdentityModel {
    static final int GRID = 7;
    static final int BINS = 256;
    static final int CELL_COUNT = GRID * GRID;
    static final int MIN_CONSISTENT_CELLS = 40;
    static final int MIN_CONSISTENT_CORE_CELLS = 21;
    private static final float MAX_GEOMETRY_PEAK = 3.2f;
    private static final float[] GEOMETRY_FLOORS = {
            0.040f, 0.020f, 0.025f, 0.035f, 0.028f, 0.030f, 0.022f
    };

    final float[] textureCentroid;
    final float[] cellWeights;
    final float[] cellLimits;
    final float[] geometryCentroid;
    final float[] geometryScales;
    final float textureThreshold;
    final float geometryThreshold;

    IdentityModel(float[] textureCentroid, float[] cellWeights, float[] cellLimits,
            float[] geometryCentroid, float[] geometryScales, float textureThreshold,
            float geometryThreshold) {
        if (textureCentroid.length != LbpDescriptor.LENGTH
                || cellWeights.length != CELL_COUNT
                || cellLimits.length != CELL_COUNT
                || geometryCentroid.length != FaceGeometry.LENGTH
                || geometryScales.length != FaceGeometry.LENGTH) {
            throw new IllegalArgumentException("Invalid identity model dimensions");
        }
        this.textureCentroid = textureCentroid;
        this.cellWeights = cellWeights;
        this.cellLimits = cellLimits;
        this.geometryCentroid = geometryCentroid;
        this.geometryScales = geometryScales;
        this.textureThreshold = textureThreshold;
        this.geometryThreshold = geometryThreshold;
    }

    static IdentityModel enroll(List<float[]> textures, List<float[]> geometries) {
        if (textures.size() < 6 || textures.size() != geometries.size()) {
            throw new IllegalArgumentException("Insufficient paired enrollment samples");
        }
        float[] textureCentroid = LbpDescriptor.mean(textures);
        float[] geometryCentroid = mean(geometries, FaceGeometry.LENGTH);
        float[][] enrollmentCellDistances = new float[textures.size()][CELL_COUNT];
        float[] cellWeights = new float[CELL_COUNT];
        float[] cellLimits = new float[CELL_COUNT];
        float rawWeightSum = 0f;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            float[] values = new float[textures.size()];
            for (int sample = 0; sample < textures.size(); sample++) {
                float distance = cellDistance(textures.get(sample), textureCentroid, cell);
                enrollmentCellDistances[sample][cell] = distance;
                values[sample] = distance;
            }
            float p90 = percentile(values, 0.90f);
            float weight = 1f / (0.08f + p90);
            cellWeights[cell] = weight;
            rawWeightSum += weight;
            cellLimits[cell] = clamp(p90 * 2.2f + 0.08f, 0.34f, 0.82f);
        }
        float rawWeightMean = rawWeightSum / CELL_COUNT;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            cellWeights[cell] = clamp(cellWeights[cell] / rawWeightMean, 0.60f, 1.80f);
        }

        float[] textureScores = new float[textures.size()];
        for (int sample = 0; sample < textures.size(); sample++) {
            textureScores[sample] = weightedMean(enrollmentCellDistances[sample], cellWeights);
        }
        float textureThreshold = clamp(percentile(textureScores, 0.90f) * 1.60f + 0.035f,
                0.28f, 0.32f);

        float[] geometryScales = new float[FaceGeometry.LENGTH];
        for (int dimension = 0; dimension < FaceGeometry.LENGTH; dimension++) {
            float[] deviations = new float[geometries.size()];
            for (int sample = 0; sample < geometries.size(); sample++) {
                deviations[sample] = Math.abs(geometries.get(sample)[dimension]
                        - geometryCentroid[dimension]);
            }
            geometryScales[dimension] = Math.max(GEOMETRY_FLOORS[dimension],
                    percentile(deviations, 0.90f) * 2.4f
                            + GEOMETRY_FLOORS[dimension] * 0.20f);
        }
        float[] geometryScores = new float[geometries.size()];
        for (int sample = 0; sample < geometries.size(); sample++) {
            geometryScores[sample] = geometryScore(geometries.get(sample), geometryCentroid,
                    geometryScales).mean;
        }
        float geometryThreshold = clamp(percentile(geometryScores, 0.90f) * 1.65f + 0.30f,
                1.35f, 2.00f);
        return new IdentityModel(textureCentroid, cellWeights, cellLimits, geometryCentroid,
                geometryScales, textureThreshold, geometryThreshold);
    }

    Match compare(float[] texture, float[] geometry, float activeTextureThreshold) {
        if (texture == null || texture.length != LbpDescriptor.LENGTH
                || geometry == null || geometry.length != FaceGeometry.LENGTH) {
            return Match.rejected();
        }
        float[] distances = new float[CELL_COUNT];
        int consistentCells = 0;
        int consistentCoreCells = 0;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            float distance = cellDistance(texture, textureCentroid, cell);
            distances[cell] = distance;
            int row = cell / GRID;
            int column = cell % GRID;
            boolean core = row >= 1 && row <= 5 && column >= 1 && column <= 5;
            float activeCellLimit = Math.max(cellLimits[cell], activeTextureThreshold * 1.45f);
            if (distance <= activeCellLimit) {
                consistentCells++;
                if (core) {
                    consistentCoreCells++;
                }
            }
        }
        float textureScore = weightedMean(distances, cellWeights);
        GeometryScore geometryScore = geometryScore(geometry, geometryCentroid, geometryScales);
        boolean accepted = textureScore <= activeTextureThreshold
                && consistentCells >= MIN_CONSISTENT_CELLS
                && consistentCoreCells >= MIN_CONSISTENT_CORE_CELLS
                && geometryScore.mean <= geometryThreshold
                && geometryScore.peak <= MAX_GEOMETRY_PEAK;
        return new Match(textureScore, geometryScore.mean, geometryScore.peak,
                consistentCells, consistentCoreCells, accepted);
    }

    private static float cellDistance(float[] first, float[] second, int cell) {
        int offset = cell * BINS;
        double sum = 0d;
        for (int bin = 0; bin < BINS; bin++) {
            double a = first[offset + bin];
            double b = second[offset + bin];
            double delta = a - b;
            sum += (delta * delta) / (a + b + 1e-8d);
        }
        return (float) (0.5d * sum);
    }

    private static float weightedMean(float[] values, float[] weights) {
        double weightedSum = 0d;
        double weightSum = 0d;
        for (int i = 0; i < values.length; i++) {
            weightedSum += values[i] * weights[i];
            weightSum += weights[i];
        }
        return (float) (weightedSum / Math.max(1e-8d, weightSum));
    }

    private static GeometryScore geometryScore(float[] value, float[] centroid,
            float[] scales) {
        float sum = 0f;
        float peak = 0f;
        for (int i = 0; i < value.length; i++) {
            float normalized = Math.abs(value[i] - centroid[i])
                    / Math.max(1e-5f, scales[i]);
            sum += normalized;
            peak = Math.max(peak, normalized);
        }
        return new GeometryScore(sum / value.length, peak);
    }

    private static float[] mean(List<float[]> samples, int length) {
        float[] result = new float[length];
        for (float[] sample : samples) {
            if (sample.length != length) {
                throw new IllegalArgumentException("Invalid enrollment sample dimensions");
            }
            for (int i = 0; i < length; i++) {
                result[i] += sample[i];
            }
        }
        for (int i = 0; i < length; i++) {
            result[i] /= samples.size();
        }
        return result;
    }

    private static float percentile(float[] source, float percentile) {
        float[] values = source.clone();
        Arrays.sort(values);
        int index = Math.min(values.length - 1,
                Math.max(0, (int) Math.ceil(values.length * percentile) - 1));
        return values[index];
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Match {
        final float textureScore;
        final float geometryScore;
        final float geometryPeak;
        final int consistentCells;
        final int consistentCoreCells;
        final boolean accepted;

        Match(float textureScore, float geometryScore, float geometryPeak,
                int consistentCells, int consistentCoreCells, boolean accepted) {
            this.textureScore = textureScore;
            this.geometryScore = geometryScore;
            this.geometryPeak = geometryPeak;
            this.consistentCells = consistentCells;
            this.consistentCoreCells = consistentCoreCells;
            this.accepted = accepted;
        }

        static Match rejected() {
            return new Match(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, 0, 0,
                    false);
        }
    }

    private static final class GeometryScore {
        final float mean;
        final float peak;

        GeometryScore(float mean, float peak) {
            this.mean = mean;
            this.peak = peak;
        }
    }
}
