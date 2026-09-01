CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE user_food
(
    user_food_id UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES user_account (user_id),

    name            VARCHAR(160) NOT NULL,
    normalized_name VARCHAR(160) NOT NULL,
    brand           VARCHAR(120),
    barcode         VARCHAR(14),
    description     VARCHAR(1000),

    basis_amount NUMERIC(18, 6) NOT NULL,
    basis_unit   VARCHAR(20)    NOT NULL,

    calories_kcal              NUMERIC(18, 6),
    protein_grams              NUMERIC(18, 6),
    carbohydrates_grams        NUMERIC(18, 6),
    fat_grams                  NUMERIC(18, 6),
    fiber_grams                NUMERIC(18, 6),
    sugars_grams               NUMERIC(18, 6),
    saturated_fat_grams        NUMERIC(18, 6),
    sodium_milligrams          NUMERIC(18, 6),
    salt_grams                 NUMERIC(18, 6),

    default_serving_amount NUMERIC(18, 6),
    default_serving_unit   VARCHAR(20),

    grams_per_piece          NUMERIC(18, 6),
    milliliters_per_piece    NUMERIC(18, 6),
    grams_per_serving        NUMERIC(18, 6),
    milliliters_per_serving  NUMERIC(18, 6),

    source_type        VARCHAR(40)  NOT NULL,
    source_provider    VARCHAR(120),
    source_external_id VARCHAR(255),
    source_notes       VARCHAR(1000),
    source_copied_at   DATE,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    version    BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT uq_user_food_id_user UNIQUE (user_food_id, user_id),
    CONSTRAINT ck_user_food_name_nonblank CHECK (btrim(name) <> '' AND btrim(normalized_name) <> ''),
    CONSTRAINT ck_user_food_barcode CHECK (barcode IS NULL OR barcode ~ '^[0-9]{8,14}$'),
    CONSTRAINT ck_user_food_basis_amount CHECK (basis_amount > 0 AND basis_amount <= 1000000),
    CONSTRAINT ck_user_food_basis_unit CHECK (
        basis_unit IN ('GRAM', 'KILOGRAM', 'MILLILITER', 'LITER', 'PIECE', 'SERVING')
    ),
    CONSTRAINT ck_user_food_nutrients CHECK (
        (calories_kcal IS NULL OR calories_kcal BETWEEN 0 AND 1000000) AND
        (protein_grams IS NULL OR protein_grams BETWEEN 0 AND 1000000) AND
        (carbohydrates_grams IS NULL OR carbohydrates_grams BETWEEN 0 AND 1000000) AND
        (fat_grams IS NULL OR fat_grams BETWEEN 0 AND 1000000) AND
        (fiber_grams IS NULL OR fiber_grams BETWEEN 0 AND 1000000) AND
        (sugars_grams IS NULL OR sugars_grams BETWEEN 0 AND 1000000) AND
        (saturated_fat_grams IS NULL OR saturated_fat_grams BETWEEN 0 AND 1000000) AND
        (sodium_milligrams IS NULL OR sodium_milligrams BETWEEN 0 AND 1000000) AND
        (salt_grams IS NULL OR salt_grams BETWEEN 0 AND 1000000)
    ),
    CONSTRAINT ck_user_food_default_serving_pair CHECK (
        (default_serving_amount IS NULL) = (default_serving_unit IS NULL)
    ),
    CONSTRAINT ck_user_food_default_serving_amount CHECK (
        default_serving_amount IS NULL OR
        (default_serving_amount > 0 AND default_serving_amount <= 1000000)
    ),
    CONSTRAINT ck_user_food_default_serving_unit CHECK (
        default_serving_unit IS NULL OR
        default_serving_unit IN ('GRAM', 'KILOGRAM', 'MILLILITER', 'LITER', 'PIECE', 'SERVING')
    ),
    CONSTRAINT ck_user_food_piece_conversion CHECK (
        (grams_per_piece IS NULL OR (grams_per_piece > 0 AND grams_per_piece <= 1000000)) AND
        (milliliters_per_piece IS NULL OR (milliliters_per_piece > 0 AND milliliters_per_piece <= 1000000)) AND
        NOT (grams_per_piece IS NOT NULL AND milliliters_per_piece IS NOT NULL)
    ),
    CONSTRAINT ck_user_food_serving_conversion CHECK (
        (grams_per_serving IS NULL OR (grams_per_serving > 0 AND grams_per_serving <= 1000000)) AND
        (milliliters_per_serving IS NULL OR (milliliters_per_serving > 0 AND milliliters_per_serving <= 1000000)) AND
        NOT (grams_per_serving IS NOT NULL AND milliliters_per_serving IS NOT NULL)
    ),
    CONSTRAINT ck_user_food_default_serving_conversion CHECK (
        default_serving_unit IS NULL OR
        (basis_unit IN ('GRAM', 'KILOGRAM') AND default_serving_unit IN ('GRAM', 'KILOGRAM')) OR
        (basis_unit IN ('MILLILITER', 'LITER') AND default_serving_unit IN ('MILLILITER', 'LITER')) OR
        (basis_unit = default_serving_unit AND basis_unit IN ('PIECE', 'SERVING')) OR
        (basis_unit IN ('GRAM', 'KILOGRAM') AND default_serving_unit = 'PIECE' AND grams_per_piece IS NOT NULL) OR
        (basis_unit = 'PIECE' AND default_serving_unit IN ('GRAM', 'KILOGRAM') AND grams_per_piece IS NOT NULL) OR
        (basis_unit IN ('MILLILITER', 'LITER') AND default_serving_unit = 'PIECE' AND milliliters_per_piece IS NOT NULL) OR
        (basis_unit = 'PIECE' AND default_serving_unit IN ('MILLILITER', 'LITER') AND milliliters_per_piece IS NOT NULL) OR
        (basis_unit IN ('GRAM', 'KILOGRAM') AND default_serving_unit = 'SERVING' AND grams_per_serving IS NOT NULL) OR
        (basis_unit = 'SERVING' AND default_serving_unit IN ('GRAM', 'KILOGRAM') AND grams_per_serving IS NOT NULL) OR
        (basis_unit IN ('MILLILITER', 'LITER') AND default_serving_unit = 'SERVING' AND milliliters_per_serving IS NOT NULL) OR
        (basis_unit = 'SERVING' AND default_serving_unit IN ('MILLILITER', 'LITER') AND milliliters_per_serving IS NOT NULL)
    ),
    CONSTRAINT ck_user_food_source_type CHECK (
        source_type IN ('USER_ENTERED', 'PRODUCT_LABEL', 'EXTERNAL_DATABASE', 'AI_ESTIMATE', 'IMPORTED')
    ),
    CONSTRAINT ck_user_food_source_external_id CHECK (
        source_external_id IS NULL OR source_provider IS NOT NULL
    ),
    CONSTRAINT ck_user_food_external_source CHECK (
        source_type <> 'EXTERNAL_DATABASE' OR
        (source_provider IS NOT NULL AND source_external_id IS NOT NULL)
    ),
    CONSTRAINT ck_user_food_timestamps CHECK (
        updated_at >= created_at AND (deleted_at IS NULL OR deleted_at >= created_at)
    )
);

CREATE TABLE user_food_alias
(
    alias_id         UUID PRIMARY KEY,
    user_food_id     UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    alias            VARCHAR(120) NOT NULL,
    normalized_alias VARCHAR(120) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    deleted_at       TIMESTAMPTZ,

    CONSTRAINT fk_user_food_alias_food_owner
        FOREIGN KEY (user_food_id, user_id)
            REFERENCES user_food (user_food_id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_user_food_alias_nonblank CHECK (
        btrim(alias) <> '' AND btrim(normalized_alias) <> ''
    ),
    CONSTRAINT ck_user_food_alias_timestamps CHECK (
        deleted_at IS NULL OR deleted_at >= created_at
    )
);

CREATE UNIQUE INDEX uq_user_food_active_barcode
    ON user_food (user_id, barcode)
    WHERE barcode IS NOT NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX uq_user_food_alias_active_normalized
    ON user_food_alias (user_id, normalized_alias)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_user_food_active_list_name
    ON user_food (user_id, normalized_name, user_food_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_user_food_active_created
    ON user_food (user_id, created_at DESC, user_food_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_user_food_active_updated
    ON user_food (user_id, updated_at DESC, user_food_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_user_food_active_name_prefix
    ON user_food (user_id, normalized_name varchar_pattern_ops)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_user_food_active_name_trgm
    ON user_food USING GIN (normalized_name gin_trgm_ops)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_user_food_alias_active_food
    ON user_food_alias (user_food_id, normalized_alias)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_user_food_alias_active_prefix
    ON user_food_alias (user_id, normalized_alias varchar_pattern_ops)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_user_food_alias_active_trgm
    ON user_food_alias USING GIN (normalized_alias gin_trgm_ops)
    WHERE deleted_at IS NULL;
