from fastapi import FastAPI

app = FastAPI(title="PantryChef AI service")


@app.get("/internal/v1/health")
def health():
    return {"status": "ok"}
