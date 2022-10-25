


public class TimeMeasure {
    double duration;

    public double getDuration() {
        return duration;
    }

    public void setDuration(double duration) {
        this.duration = duration;
    }

    public TimeMeasure(double duration) {
        this.duration = duration;
    }

    @Override
    public String toString() {
        return "TimeMeasure{" +
                "duration=" + duration +
                '}';
    }
}
