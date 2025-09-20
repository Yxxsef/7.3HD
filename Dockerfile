FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY requirements.txt .


RUN python -m pip install --upgrade \
    "pip>=25.0" \
    "setuptools>=78.1.1" \
    wheel

RUN python -m pip install -r requirements.txt

COPY app app

EXPOSE 8000
CMD ["uvicorn","app.main:app","--host","0.0.0.0","--port","8000"]
