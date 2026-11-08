package com.yusd.pixel2dface;

import java.util.Arrays;
import java.util.List;

/**
 * Personalized two-signal identity model built from texture and landmark geometry.
 * It reuses one ML Kit result and one LBP descriptor, so it adds no model inference.
 */
final class IdentityModel implements IdentityTemplate {
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
    // These legacy package fields are serialization snapshots. Matching owns separate arrays,
    // so a caller cannot change the enrolled identity after constructor validation.
    private final float[] matchingTexture;
    private final float[] matchingWeights;
    private final float[] matchingLimits;
    private final float[] matchingGeometry;
    private final float[] matchingScales;
    private final double weightSum;

    IdentityModel(float[] textureCentroid, float[] cellWeights, float[] cellLimits,
            float[] geometryCentroid, float[] geometryScales, float textureThreshold,
            float geometryThreshold) {
        if (textureCentroid == null || cellWeights == null || cellLimits == null
                || geometryCentroid == null || geometryScales == null
                || textureCentroid.length != LbpDescriptor.LENGTH
                || cellWeights.length != CELL_COUNT
                || cellLimits.length != CELL_COUNT
                || geometryCentroid.length != FaceGeometry.LENGTH
                || geometryScales.length != FaceGeometry.LENGTH) {
            throw new IllegalArgumentException("Invalid identity model dimensions");
        }
        requireRange(textureCentroid, 0f, 1f);
        requireRange(cellWeights, 0.60f, 1.80f);
        requireRange(cellLimits, 0.34f, 0.82f);
        requireRange(geometryCentroid, -2f, 2f);
        requireRange(geometryScales, 0.00001f, 1f);
        if (!Float.isFinite(textureThreshold) || textureThreshold < 0.28f
                || textureThreshold > 0.35f || !Float.isFinite(geometryThreshold)
                || geometryThreshold < 1f || geometryThreshold > 2.5f) {
            throw new IllegalArgumentException("Invalid identity model thresholds");
        }
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            float mass = 0f;
            for (int bin = 0; bin < BINS; bin++) {
                mass += textureCentroid[cell * BINS + bin];
            }
            if (Math.abs(mass - 1f) > 0.02f) {
                throw new IllegalArgumentException("Invalid texture histogram");
            }
        }
        this.textureCentroid = textureCentroid.clone();
        this.cellWeights = cellWeights.clone();
        this.cellLimits = cellLimits.clone();
        this.geometryCentroid = geometryCentroid.clone();
        this.geometryScales = geometryScales.clone();
        matchingTexture = this.textureCentroid.clone();
        matchingWeights = this.cellWeights.clone();
        matchingLimits = this.cellLimits.clone();
        matchingGeometry = this.geometryCentroid.clone();
        matchingScales = this.geometryScales.clone();
        this.textureThreshold = textureThreshold;
        this.geometryThreshold = geometryThreshold;
        double sum = 0d;
        for (float weight : this.cellWeights) {
            sum += weight;
        }
        weightSum = sum;
    }

    static IdentityModel enroll(List<float[]> textures, List<float[]> geometries) {
        if (textures == null || geometries == null || textures.size() < 6
                || textures.size() != geometries.size()) {
            throw new IllegalArgumentException("Insufficient paired enrollment samples");
        }
        for (int i = 0; i < textures.size(); i++) {
            if (!validLiveInputs(textures.get(i), geometries.get(i))) {
                throw new IllegalArgumentException("Invalid paired enrollment sample");
            }
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

    @Override public float maximumThreshold() { return textureThreshold; }
    @Override public float minimumThreshold() { return 0.24f; }
    @Override public boolean validSample(float[] descriptor, float[] geometry) {
        return validLiveInputs(descriptor, geometry);
    }
    @Override public IdentityEvidence evaluate(float[] descriptor, float[] geometry, float threshold) {
        Match match = compare(descriptor, geometry, threshold);
        return new IdentityEvidence(match.textureScore, match.accepted,
                match.isHighConfidence(threshold, geometryThreshold));
    }

    Match compare(float[] texture, float[] geometry, float activeTextureThreshold) {
        if (!validLiveInputs(texture, geometry)) return Match.rejected();
        return compareValidated(texture, geometry, activeTextureThreshold);
    }

    /** Engine already checked histogram mass and geometry before advancing any evidence. */
    Match compareValidated(float[] texture, float[] geometry, float activeTextureThreshold) {
        if (!Float.isFinite(activeTextureThreshold) || activeTextureThreshold <= 0f
                || activeTextureThreshold > textureThreshold) {
            return Match.rejected();
        }
        double weightedSum = 0d;
        int consistentCells = 0;
        int consistentCoreCells = 0;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            float distance = cellDistance(texture, matchingTexture, cell);
            weightedSum += distance * matchingWeights[cell];
            int row = cell / GRID;
            int column = cell % GRID;
            boolean core = row >= 1 && row <= 5 && column >= 1 && column <= 5;
            float activeCellLimit = Math.max(matchingLimits[cell], activeTextureThreshold * 1.45f);
            if (distance <= activeCellLimit) {
                consistentCells++;
                if (core) {
                    consistentCoreCells++;
                }
            }
        }
        float textureScore = (float) (weightedSum / weightSum);
        GeometryScore geometryScore = geometryScore(geometry, matchingGeometry, matchingScales);
        boolean accepted = textureScore <= activeTextureThreshold
                && consistentCells >= MIN_CONSISTENT_CELLS
                && consistentCoreCells >= MIN_CONSISTENT_CORE_CELLS
                && geometryScore.mean <= geometryThreshold
                && geometryScore.peak <= MAX_GEOMETRY_PEAK;
        return new Match(textureScore, geometryScore.mean, geometryScore.peak,
                consistentCells, consistentCoreCells, accepted);
    }

    static boolean validLiveInputs(float[] texture, float[] geometry) {
        if (texture == null || texture.length != LbpDescriptor.LENGTH
                || !FaceGeometry.isValid(geometry)) return false;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            double mass = 0d;
            int end = (cell + 1) * BINS;
            for (int bin = cell * BINS; bin < end; bin++) {
                float value = texture[bin];
                if (!Float.isFinite(value) || value < 0f || value > 1f) return false;
                mass += value;
            }
            if (Math.abs(mass - 1d) > 0.02d) return false;
        }
        return true;
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

    private static void requireRange(float[] values, float minimum, float maximum) {
        for (float value : values) {
            if (!Float.isFinite(value) || value < minimum || value > maximum) {
                throw new IllegalArgumentException("Invalid identity model values");
            }
        }
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

        boolean isHighConfidence(float activeThreshold, float geometryThreshold) {
            // Extra margins are only for saving the fifth observation. Normal matching
            // still requires all original texture, geometry, and regional checks.
            return accepted && Float.isFinite(activeThreshold) && activeThreshold > 0f
                    && Float.isFinite(geometryThreshold) && geometryThreshold > 0f
                    && Float.isFinite(textureScore) && textureScore >= 0f
                    && Float.isFinite(geometryScore) && geometryScore >= 0f
                    && Float.isFinite(geometryPeak) && geometryPeak >= 0f
                    && textureScore <= activeThreshold - 0.05f
                    && geometryScore <= Math.min(0.75f, geometryThreshold * 0.55f)
                    && geometryPeak <= 1.8f && consistentCells >= 44
                    && consistentCoreCells >= 24;
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
