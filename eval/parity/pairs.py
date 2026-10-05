"""100 (query, passage) pairs written by hand from generic public-knowledge topics.

25 queries, each with one relevant passage; every query is also paired with three passages that
belong to other queries (offsets 1, 7, 13) so the score range covers relevant and irrelevant text.
No text comes from a user's files or phone.
"""

_QP = [
    ("how to boil an egg", "Place eggs in boiling water for nine minutes for hard boiled eggs, then cool them in ice water before peeling."),
    ("capital of australia", "Canberra is the capital city of Australia, located between Sydney and Melbourne in the Australian Capital Territory."),
    ("reset wifi router", "To reset the router, hold the small reset button for ten seconds until the lights blink, then set up the network again."),
    ("tomato pasta sauce recipe", "Simmer crushed tomatoes with garlic, olive oil and basil for twenty minutes, then toss with cooked spaghetti."),
    ("how many continents are there", "By the most common convention there are seven continents: Africa, Antarctica, Asia, Europe, North America, Oceania and South America."),
    ("clear browser cache", "Open the browser settings, choose privacy, and select clear browsing data to remove cached images and files."),
    ("longest river in the world", "The Nile and the Amazon are the two candidates for the longest river, with measurements around 6,600 kilometres."),
    ("how to make pancakes", "Whisk flour, milk, eggs and a pinch of salt into a smooth batter and fry ladlefuls in a hot buttered pan."),
    ("python read a text file", "Use the open function with a with statement, then call read or iterate over the file object line by line."),
    ("what causes the seasons", "Seasons are caused by the tilt of the Earth's axis as it orbits the Sun, changing how directly sunlight reaches each hemisphere."),
    ("fix a flat bicycle tire", "Remove the wheel, pry off the tire, patch or replace the inner tube, check for thorns, and reinflate."),
    ("highest mountain on earth", "Mount Everest, on the border of Nepal and China, rises about 8,849 metres above sea level."),
    ("convert celsius to fahrenheit", "Multiply the temperature in Celsius by nine fifths and add thirty two to get degrees Fahrenheit."),
    ("how to bake sourdough bread", "Mix flour, water and active starter, let the dough rise overnight, shape it and bake in a very hot covered pot."),
    ("update android phone software", "Open Settings, tap System, then Software update, and install the newest version while connected to power."),
    ("what is photosynthesis", "Photosynthesis is the process by which plants use sunlight, water and carbon dioxide to make sugar and release oxygen."),
    ("best way to learn a language", "Regular short practice, listening to native speakers, and speaking from the first week help most learners progress."),
    ("how to change a light bulb", "Switch off the power, let the bulb cool, unscrew it counterclockwise and screw in a bulb of the same base type."),
    ("population of japan", "Japan has a population of roughly 124 million people, most of whom live in cities on the island of Honshu."),
    ("vegetable soup recipe", "Sauté onions and carrots, add diced potatoes, celery and stock, then simmer until the vegetables are tender."),
    ("bluetooth headphones not pairing", "Put the headphones in pairing mode, forget the old device on your phone, and try connecting again."),
    ("who painted the mona lisa", "The Mona Lisa was painted by Leonardo da Vinci in the early sixteenth century and hangs in the Louvre in Paris."),
    ("how to save battery on a laptop", "Lower screen brightness, close background apps, enable battery saver mode and unplug unused peripherals."),
    ("how far is the moon", "The Moon orbits the Earth at an average distance of about 384,000 kilometres."),
    ("guacamole ingredients", "Mash ripe avocados with lime juice, salt, chopped onion, cilantro and a little chili."),
]

PAIRS = []
for _i, (_q, _p) in enumerate(_QP):
    PAIRS.append((_q, _p))
    for _off in (1, 7, 13):
        PAIRS.append((_q, _QP[(_i + _off) % len(_QP)][1]))

assert len(PAIRS) == 100
