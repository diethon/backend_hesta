-- ScheduleRunner queries active schedules by time every minute.
CREATE INDEX IF NOT EXISTS idx_scene_schedules_due ON scene_schedules(scheduled_time) WHERE is_active;
CREATE INDEX IF NOT EXISTS idx_automation_schedules_due ON automation_schedules(scheduled_time) WHERE is_active;
