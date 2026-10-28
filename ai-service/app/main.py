import asyncio
import hmac
import logging
from contextlib import asynccontextmanager
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from app import planner
from app.config import setting
from app.db import connect
from app.embeddings import Embed, embed
from app.grocery import Stock
from app.retrieval import PantryItem, retrieve

log = logging.getLogger("pantrychef.ai")


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Load the embedding model before serving, so the first request is not slow. A failure is not fatal:
    # retrieval then answers 503 MODEL_UNAVAILABLE and other routes keep working.
    try:
        await asyncio.to_thread(embed, ["warm up"])
    except Exception as exc:  # noqa: BLE001
        log.warning("Embedding model not loaded at startup: %s", type(exc).__name__)
    yield


app = FastAPI(title="PantryChef AI service", lifespan=lifespan)


def require_service_token(x_service_token: str | None = Header(default=None)) -> None:
    """Only the PantryChef backend may call /internal routes (D-009); a hidden port is not security."""
    expected = setting("SERVICE_TO_SERVICE_SECRET")
    if not expected or len(expected) < 32:
        raise HTTPException(503, detail=("SERVICE_NOT_CONFIGURED", "Internal authentication is not configured."))
    if not x_service_token or not hmac.compare_digest(x_service_token.encode(), expected.encode()):
        raise HTTPException(401, detail=("UNAUTHENTICATED", "Missing or invalid service credentials."))


internal = APIRouter(prefix="/internal/v1", dependencies=[Depends(require_service_token)])


def _error(status: int, code: str, message: str, field_errors: dict | None = None) -> JSONResponse:
    body = {"code": code, "message": message}
    if field_errors:
        body["fieldErrors"] = field_errors
    return JSONResponse(status_code=status, content={"error": body})


@app.exception_handler(HTTPException)
async def http_error(request: Request, exc: HTTPException):
    if isinstance(exc.detail, tuple):
        return _error(exc.status_code, *exc.detail)
    return _error(exc.status_code, "ERROR", "Request failed.")


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError):
    # Same policy as the backend (D-011): invalid input is 400, never echoing the submitted values.
    fields = {".".join(str(p) for p in e["loc"][1:]) or "body": e["msg"] for e in exc.errors()}
    return _error(400, "VALIDATION_ERROR", "Check the request fields.", fields)


@app.exception_handler(Exception)
async def unexpected_error(request: Request, exc: Exception):
    log.error("Unhandled %s", type(exc).__name__)
    return _error(500, "INTERNAL_ERROR", "Something went wrong.")


@app.get("/internal/v1/health")
def health():
    return {"status": "ok"}


Diet = Literal["vegetarian", "vegan", "gluten_free"]
Allergen = Literal["peanuts", "tree_nuts", "dairy", "eggs", "gluten", "soy", "fish", "shellfish", "sesame"]


Unit = Literal["g", "kg", "ml", "l", "piece", "pack", "bunch", "dozen"]


class PantryIngredient(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=100)
    daysToExpiry: int | None = Field(default=None, ge=-3650, le=3650)
    quantity: float | None = Field(default=None, gt=0, le=1_000_000_000)
    unit: Unit | None = None


def _stock(pantry: list[PantryIngredient]) -> list[Stock]:
    return [Stock(p.name, p.quantity, p.unit) for p in pantry if p.quantity is not None and p.unit is not None]


class RetrieveRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    pantry: list[PantryIngredient] = Field(max_length=500)
    diets: list[Diet] = Field(default_factory=list, max_length=10)
    allergens: list[Allergen] = Field(default_factory=list, max_length=20)
    limit: int = Field(default=10, ge=1, le=50)


def get_embed() -> Embed:
    return embed


@internal.post("/recipes/retrieve")
def retrieve_recipes(request: RetrieveRequest, embedder: Embed = Depends(get_embed)):
    with connect() as conn:
        try:
            return retrieve(conn, embedder, [PantryItem(p.name, p.daysToExpiry) for p in request.pantry],
                            request.diets, request.allergens, request.limit)
        except (OSError, RuntimeError) as exc:
            log.warning("Retrieval unavailable: %s", type(exc).__name__)
            raise HTTPException(503, detail=("MODEL_UNAVAILABLE", "Recipe search is not ready.")) from exc


class PlanRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    pantry: list[PantryIngredient] = Field(max_length=500)
    diets: list[Diet] = Field(default_factory=list, max_length=10)
    allergens: list[Allergen] = Field(default_factory=list, max_length=20)
    days: int = Field(default=7, ge=1, le=7)


class GroceryRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    recipeIds: list[UUID] = Field(max_length=50)
    pantry: list[PantryIngredient] = Field(max_length=500)


@internal.post("/meal-plans/generate")
def generate_plan(request: PlanRequest, embedder: Embed = Depends(get_embed)):
    with connect() as conn:
        try:
            return planner.generate(conn, embedder, [PantryItem(p.name, p.daysToExpiry) for p in request.pantry],
                                    _stock(request.pantry), request.diets, request.allergens, request.days)
        except (OSError, RuntimeError) as exc:
            log.warning("Planning unavailable: %s", type(exc).__name__)
            raise HTTPException(503, detail=("MODEL_UNAVAILABLE", "Meal planning is not ready.")) from exc


@internal.post("/grocery/compute")
def compute_grocery(request: GroceryRequest):
    with connect() as conn:
        return planner.grocery_for(conn, [str(i) for i in request.recipeIds], _stock(request.pantry))


app.include_router(internal)
