FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

RUN useradd --create-home --uid 10001 linjian \
    && mkdir /data \
    && chown linjian:linjian /data

COPY --chown=linjian:linjian server/linjian_server.py ./linjian_server.py

USER linjian

EXPOSE 8513

CMD ["python", "linjian_server.py"]
