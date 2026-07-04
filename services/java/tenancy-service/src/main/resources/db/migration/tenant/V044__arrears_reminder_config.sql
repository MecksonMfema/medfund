-- =====================================================================
-- V044: Arrears reminder cadence on dunning_config
-- =====================================================================
-- Adds tenant-configurable knobs for the arrears-notification loop:
--   * whether reminders fire at all (auto_remind — opt-in like
--     auto_suspend / auto_write_off);
--   * how many days BEFORE the SUSPENDED threshold reminders kick in
--     (reminder_lead_days);
--   * how often they repeat while overdue (reminder_interval_days);
--   * whether to keep reminding after the row is suspended, up until
--     deactivation (reminder_continue_past_suspension).
--
-- Reminder-day math is deterministic from days_overdue + these knobs;
-- no state table is needed because the arrears executor runs daily
-- and picks the rows whose age lines up with a reminder tick.

ALTER TABLE dunning_config
    ADD COLUMN IF NOT EXISTS auto_remind                     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS reminder_lead_days              INTEGER NOT NULL DEFAULT 7,
    ADD COLUMN IF NOT EXISTS reminder_interval_days          INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS reminder_continue_past_suspension BOOLEAN NOT NULL DEFAULT TRUE;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'dunning_config_reminder_lead_check') THEN
        ALTER TABLE dunning_config
            ADD CONSTRAINT dunning_config_reminder_lead_check CHECK (reminder_lead_days >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'dunning_config_reminder_interval_check') THEN
        ALTER TABLE dunning_config
            ADD CONSTRAINT dunning_config_reminder_interval_check CHECK (reminder_interval_days >= 1);
    END IF;
END $$;
