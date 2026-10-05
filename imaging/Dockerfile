FROM python:3.12-slim
RUN pip install --no-cache-dir numpy==2.* scipy==1.* opencv-python-headless==4.* onnxruntime==1.*
WORKDIR /app
COPY wm.py app.py ./
USER 1000:1000
EXPOSE 8090
CMD ["python", "app.py"]
