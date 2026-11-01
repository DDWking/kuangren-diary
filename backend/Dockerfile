# 狂人日记云端后端（生产容器）
# 构建：docker build -t kuangren-backend backend/
# 运行：docker run -d --name kuangren-backend -p 127.0.0.1:8090:8090 \
#         -e KUANGREN_TOKEN=<token> -v kuangren-data:/app/data kuangren-backend
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY requirements.txt ./
RUN python -m pip install --no-cache-dir --upgrade pip && \
    python -m pip install --no-cache-dir -r requirements.txt

COPY app ./app

RUN mkdir -p /app/data
ENV KUANGREN_DATA_DIR=/app/data \
    KUANGREN_HOST=0.0.0.0 \
    KUANGREN_PORT=8090

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request, json; r=urllib.request.urlopen('http://127.0.0.1:8090/api/v1/health', timeout=3); assert json.load(r)['ok']"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
