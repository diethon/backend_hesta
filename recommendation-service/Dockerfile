# Same Python as the pinned wheels in requirements.txt (pandas 3 / scikit-learn 1.9).
FROM python:3.13-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8 \
    PORT=8000

# psycopg2-binary ships its own libpq: no compiler or system packages needed.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Non-root. uid 1000 so a bind-mounted ./artifacts owned by the host user stays writable.
RUN useradd --uid 1000 --create-home appuser && mkdir -p /app/artifacts/homes && chown -R appuser /app/artifacts
USER appuser

EXPOSE 8000

# One worker on purpose: models are cached in process memory, a second worker would not see a retrain.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
